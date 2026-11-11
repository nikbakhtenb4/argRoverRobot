#include "hardware_runtime.h"
#include "hardware_config.h"
#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "soc/ledc_struct.h"
#include "esp_timer.h"
#include "esp_app_desc.h"
#include "driver/dac_oneshot.h"
#include "nvs.h"

static SemaphoreHandle_t lock;
static rover_control_t motion;
static rover_clearance_t clearance[4];
static dac_oneshot_handle_t dac[2];
static bool output_fault;
static rover_output_t applied;
static bool applied_valid;
static uint32_t control_ticks,control_lock_misses,apply_max_us;
static lidar_scan_t scan, completed;
static b0602_scan_builder_t builder;
static b0602_parser_t parser;
static b0602_packet_t packet;
static int64_t link_until;
static bool lidar_configured, lidar_fault = true, pwm_configured, maintenance;
static uint32_t packets, crc_errors, format_errors, uart_errors;
#define USB_RAW_BYTES 768
static uint8_t raw_ring[USB_RAW_BYTES], last_speed;
static int16_t last_offset;
static size_t raw_next, raw_count;
static uint32_t rx_bytes;
static int64_t last_rx_ms = -1;
static int pending_baud, active_baud = RASTAG_LIDAR_BAUD;
static QueueHandle_t uart_events;
static int64_t now_ms(void) { return esp_timer_get_time() / 1000; }
static uint64_t pin_mask(int pin) { return pin >= 0 && pin < 40 ? 1ULL << pin : 0; }
static bool usable_pin(int pin) {
    // Exclude flash, boot strapping and UART0 console pins on this ESP32 DevKit.
    return pin == 4 || pin == 13 || pin == 14 || (pin >= 16 && pin <= 19) || pin == 21 || pin == 22 || pin == 23
        || pin == 25 || pin == 26 || pin == 27 || pin == 32 || pin == 33;
}
static rover_inputs_t inputs(int64_t now) {
    rover_inputs_t in = {.link_live=!maintenance && !output_fault && link_until>now,
        .scan_fresh=lidar_configured && !lidar_fault && scan.complete && now>=scan.ended_ms && now-scan.ended_ms<RASTAG_LIDAR_STALE_MS};
    memcpy(in.clearance,clearance,sizeof(clearance)); return in;
}
static void cut_power(void) {
    // Relay is ACTIVE LOW. Remove hoverboard power before touching the optos/PWM.
    gpio_set_level(RASTAG_ARG_RELAY_GPIO,1);
    for (unsigned i=0;i<2;++i) if (dac[i]) dac_oneshot_output_voltage(dac[i],0);
    gpio_set_level(RASTAG_ARG_OPTO_3_GPIO,0); gpio_set_direction(RASTAG_ARG_OPTO_3_GPIO,GPIO_MODE_OUTPUT);
    gpio_set_level(RASTAG_ARG_OPTO_4_GPIO,0); gpio_set_direction(RASTAG_ARG_OPTO_4_GPIO,GPIO_MODE_OUTPUT);
}
static esp_err_t servo_duty(unsigned channel,uint32_t duty) {
    // All callers hold the hardware lock (or run before tasks start). These APIs
    // do not require the unrelated LEDC fade service or allocate a fade channel.
    esp_err_t rc=ledc_set_duty(LEDC_LOW_SPEED_MODE,(ledc_channel_t)channel,duty);
    return rc==ESP_OK?ledc_update_duty(LEDC_LOW_SPEED_MODE,(ledc_channel_t)channel):rc;
}
static void apply_output(void) {
    if (!pwm_configured) return;
    if (output_fault) { cut_power(); return; } // Fault stays latched; do not flood logs/retry failed outputs.
    if (applied_valid && applied.pwm==motion.output.pwm && applied.relay==motion.output.relay
        && applied.opto_mask==motion.output.opto_mask && applied.pulse_us[0]==motion.output.pulse_us[0]
        && applied.pulse_us[1]==motion.output.pulse_us[1]) return;
    // ESP32's ledc_update_duty spins with interrupts masked until duty_start clears.
    // At 50 Hz that can starve UART/Wi-Fi. No other writer uses these channels:
    // defer PWM changes while busy; STOP cuts power and disables outputs immediately.
    if (!motion.output.relay) cut_power();
    if (motion.output.pwm && (LEDC.channel_group[LEDC_LOW_SPEED_MODE].channel[0].conf1.duty_start
        || LEDC.channel_group[LEDC_LOW_SPEED_MODE].channel[1].conf1.duty_start)) return;
    int64_t started=esp_timer_get_time();
    if (!motion.output.relay || output_fault) cut_power();
    bool ok=!output_fault;
    for (unsigned i=0;i<2;++i) {
        uint32_t duty=motion.output.pwm?(uint32_t)motion.output.pulse_us[i]*65536u/20000u:0;
        if ((motion.output.pwm?servo_duty(i,duty):ledc_stop(LEDC_LOW_SPEED_MODE,(ledc_channel_t)i,0))!=ESP_OK) ok=false;
        uint8_t code=(motion.output.opto_mask&(1u<<i))?rover_dac_code(RASTAG_ARG_DAC_MV):0;
        if (dac_oneshot_output_voltage(dac[i],code)!=ESP_OK) ok=false;
    }
    const int pins[]={RASTAG_ARG_OPTO_3_GPIO,RASTAG_ARG_OPTO_4_GPIO};
    for (unsigned i=0;i<2;++i) {
        bool on=(motion.output.opto_mask&(4u<<i))!=0;
        if (!on && gpio_set_level(pins[i],0)!=ESP_OK) ok=false;
        if (gpio_set_direction(pins[i],on?GPIO_MODE_INPUT:GPIO_MODE_OUTPUT)!=ESP_OK) ok=false;
        // Input means genuine Hi-Z; no internal pull-up/down substitutes the board's circuit.
    }
    if (!ok) {
        output_fault=true; cut_power(); rover_stop(&motion,ROVER_OUTPUT_ERROR);
        for (unsigned i=0;i<2;++i) ledc_stop(LEDC_LOW_SPEED_MODE,(ledc_channel_t)i,0);
    } else if (gpio_set_level(RASTAG_ARG_RELAY_GPIO,motion.output.relay?0:1)!=ESP_OK) {
        output_fault=true; cut_power(); rover_stop(&motion,ROVER_OUTPUT_ERROR);
        for (unsigned i=0;i<2;++i) ledc_stop(LEDC_LOW_SPEED_MODE,(ledc_channel_t)i,0);
    }
    applied=motion.output;applied_valid=!output_fault;
    uint32_t duration=(uint32_t)(esp_timer_get_time()-started);if(duration>apply_max_us)apply_max_us=duration;
}
static void control_task(void *unused) {
    (void)unused; TickType_t wake=xTaskGetTickCount(); bool missed_lock=false;
    for (;;) {
        if (xSemaphoreTake(lock,1)==pdTRUE) {
            ++control_ticks;
            if (missed_lock) { if(motion.mode!=ROVER_OFF)rover_stop(&motion,ROVER_LOOP_STALLED);applied_valid=false;missed_lock=false; }
            rover_inputs_t in=inputs(now_ms()); rover_tick(&motion,now_ms(),&in);
            apply_output(); xSemaphoreGive(lock);
        } else { gpio_set_level(RASTAG_ARG_RELAY_GPIO,1);++control_lock_misses;missed_lock=true; }
        TickType_t period=pdMS_TO_TICKS(RASTAG_CONTROL_PERIOD_MS),tick=xTaskGetTickCount();
        if(tick-wake>period)wake=tick; // Never spin through missed periods after flash/TLS work.
        vTaskDelayUntil(&wake,period);
    }
}
static void invalidate_lidar(void) {
    b0602_reset_stream(&parser); b0602_scan_reset(&builder);
    xSemaphoreTake(lock,portMAX_DELAY); lidar_fault = true; ++uart_errors; xSemaphoreGive(lock);
}
static void lidar_task(void *unused) {
    (void)unused;
    uint8_t bytes[256]; uart_event_t event;
    for (;;) {
        xSemaphoreTake(lock,portMAX_DELAY);
        int requested_baud = pending_baud; pending_baud = 0;
        xSemaphoreGive(lock);
        if (requested_baud) {
            // Applied only by the UART owner. RX-only diagnostic; no sensor TX or motor output.
            ESP_ERROR_CHECK(uart_set_baudrate(UART_NUM_2,requested_baud));
            uart_flush_input(UART_NUM_2); xQueueReset(uart_events);
            memset(&parser,0,sizeof(parser)); memset(&builder,0,sizeof(builder));
            xSemaphoreTake(lock,portMAX_DELAY);
            memset(&scan,0,sizeof(scan)); lidar_fault = true;
            packets = crc_errors = format_errors = uart_errors = rx_bytes = 0;
            raw_count = raw_next = 0; last_rx_ms = -1; last_speed = 0; last_offset = 0;
            active_baud = requested_baud;
            xSemaphoreGive(lock);
        }
        if (!xQueueReceive(uart_events,&event,pdMS_TO_TICKS(20))) continue;
        if (event.type == UART_FIFO_OVF || event.type == UART_BUFFER_FULL || event.type == UART_PARITY_ERR
                || event.type == UART_FRAME_ERR || event.type == UART_BREAK) {
            uart_flush_input(UART_NUM_2); xQueueReset(uart_events); invalidate_lidar(); continue;
        }
        if (event.type != UART_DATA) continue;
        size_t remaining = event.size;
        while (remaining) {
            int n = uart_read_bytes(UART_NUM_2,bytes,remaining > sizeof(bytes) ? sizeof(bytes) : remaining,0);
            if (n <= 0) break;
            remaining -= (size_t)n;
            xSemaphoreTake(lock,portMAX_DELAY);
            rx_bytes += (uint32_t)n; last_rx_ms = now_ms();
            for (int i = 0; i < n; ++i) {
                raw_ring[raw_next] = bytes[i]; raw_next = (raw_next + 1) % USB_RAW_BYTES;
                if (raw_count < USB_RAW_BYTES) ++raw_count;
            }
            xSemaphoreGive(lock);
            for (int i = 0; i < n; ++i) {
                uint32_t errors_before = parser.crc_errors + parser.format_errors + parser.timeouts;
                b0602_result_t result = b0602_feed(&parser,bytes[i],now_ms(),&packet);
                bool fault = result == B0602_SENSOR_FAULT || result == B0602_REJECTED
                    || parser.crc_errors + parser.format_errors + parser.timeouts != errors_before;
                if (fault) b0602_scan_reset(&builder);
                bool full = false;
                if (result == B0602_SCAN_PACKET) {
                    if (packet.start_cdeg && (!builder.active || packet.start_cdeg / 2250 != builder.next_tooth)) fault = true;
                    full = b0602_scan_add(&builder,&packet,now_ms(),&completed);
                }
                if (fault || result != B0602_WAIT) {
                    xSemaphoreTake(lock,portMAX_DELAY);
                    if (fault) lidar_fault = true;
                    if (full) { scan = completed; lidar_fault = false; rover_scan_clearance(&scan,motion.profile.front_cdeg,clearance); }
                    packets = parser.valid_packets; crc_errors = parser.crc_errors;
                    format_errors = parser.format_errors + parser.timeouts;
                    if (result == B0602_SCAN_PACKET) { last_speed = packet.speed_raw; last_offset = packet.offset_cdeg; }
                    xSemaphoreGive(lock);
                }
            }
        }
    }
}
void hardware_init(void) {
    lock = xSemaphoreCreateMutex(); configASSERT(lock);
    // Establish safe electrical levels before allocating networking or loading NVS.
    gpio_set_level(RASTAG_ARG_RELAY_GPIO,1);
    gpio_config_t relay={.pin_bit_mask=pin_mask(RASTAG_ARG_RELAY_GPIO),.mode=GPIO_MODE_OUTPUT};
    ESP_ERROR_CHECK(gpio_config(&relay));
    gpio_config_t optos={.pin_bit_mask=pin_mask(RASTAG_ARG_OPTO_3_GPIO)|pin_mask(RASTAG_ARG_OPTO_4_GPIO),.mode=GPIO_MODE_OUTPUT};
    ESP_ERROR_CHECK(gpio_config(&optos));
    for (unsigned i=0;i<2;++i) {
        const dac_oneshot_config_t cfg={.chan_id=(dac_channel_t)i};
        ESP_ERROR_CHECK(dac_oneshot_new_channel(&cfg,&dac[i]));
    }
    cut_power();
    rover_profile_t profile; rover_profile_default(&profile); rover_init(&motion,&profile);
    ledc_timer_config_t timer={.speed_mode=LEDC_LOW_SPEED_MODE,.duty_resolution=LEDC_TIMER_16_BIT,
        .timer_num=LEDC_TIMER_0,.freq_hz=50,.clk_cfg=LEDC_AUTO_CLK};
    ESP_ERROR_CHECK(ledc_timer_config(&timer));
    const int pins[]={RASTAG_ARG_SERVO_1_GPIO,RASTAG_ARG_SERVO_2_GPIO};
    for (unsigned i=0;i<2;++i) {
        ledc_channel_config_t channel={.gpio_num=pins[i],.speed_mode=LEDC_LOW_SPEED_MODE,.channel=(ledc_channel_t)i,
            .timer_sel=LEDC_TIMER_0,.duty=0,.hpoint=0};
        ESP_ERROR_CHECK(ledc_channel_config(&channel));
    }
    pwm_configured=true; apply_output(); // PWM=0: boot does not move the servos.
    if (usable_pin(RASTAG_LIDAR_RX_GPIO)) {
        uart_config_t config = {.baud_rate=RASTAG_LIDAR_BAUD,.data_bits=UART_DATA_8_BITS,.parity=UART_PARITY_DISABLE,
            .stop_bits=UART_STOP_BITS_1,.flow_ctrl=UART_HW_FLOWCTRL_DISABLE,.source_clk=UART_SCLK_DEFAULT};
        ESP_ERROR_CHECK(uart_param_config(UART_NUM_2,&config));
        ESP_ERROR_CHECK(uart_set_pin(UART_NUM_2,UART_PIN_NO_CHANGE,RASTAG_LIDAR_RX_GPIO,UART_PIN_NO_CHANGE,UART_PIN_NO_CHANGE));
        ESP_ERROR_CHECK(uart_driver_install(UART_NUM_2,4096,0,32,&uart_events,0));
        lidar_configured = true;
        configASSERT(xTaskCreatePinnedToCore(lidar_task,"rastag-lidar",4096,NULL,8,NULL,1) == pdPASS);
    }
    configASSERT(xTaskCreatePinnedToCore(control_task,"rastag-motion",4096,NULL,10,NULL,1) == pdPASS);
}
static void usb_diagnostics_task(void *unused) {
    (void)unused;
    char command[16]; size_t used = 0; bool overflow = false;
    static uint8_t sample[USB_RAW_BYTES];
    static char hex[USB_RAW_BYTES * 2 + 1];
    static lidar_scan_t usb_scan;
    char point_line[400];
    const char *digits = "0123456789ABCDEF";
    for (;;) {
        uint8_t c;
        if (uart_read_bytes(UART_NUM_0,&c,1,pdMS_TO_TICKS(100)) != 1) continue;
        if (c == '\r') continue;
        if (c != '\n') {
            if (used < sizeof(command)-1) command[used++] = (char)c;
            else overflow = true;
            continue;
        }
        command[used] = 0;
        int requested_baud = 0;
        xSemaphoreTake(lock,portMAX_DELAY);
        bool probe_allowed=motion.mode==ROVER_OFF && !rover_profile_valid(&motion.profile) && !maintenance;
        xSemaphoreGive(lock);
        if (!overflow && probe_allowed) {
            if (!strcmp(command,"LIDAR57600?")) requested_baud = 57600;
            if (!strcmp(command,"LIDAR115200?")) requested_baud = 115200;
            if (!strcmp(command,"LIDAR230400?")) requested_baud = 230400;
            if (!strcmp(command,"LIDAR256000?")) requested_baud = 256000;
            if (!strcmp(command,"LIDAR460800?")) requested_baud = 460800;
        }
        bool raw_query = !strcmp(command,"LIDAR_RAW?") || requested_baud;
        bool query = !overflow && (!strcmp(command,"LIDAR?") || raw_query);
        if(!overflow && !strcmp(command,"MOTION?")) {
            xSemaphoreTake(lock,portMAX_DELAY);
            printf("MOTION_RUNTIME {\"ticks\":%lu,\"lock_misses\":%lu,\"apply_max_us\":%lu,\"mode\":\"%s\",\"reason\":\"%s\"}\n",
                (unsigned long)control_ticks,(unsigned long)control_lock_misses,(unsigned long)apply_max_us,
                rover_mode_name(motion.mode),rover_reason_name(motion.reason));
            xSemaphoreGive(lock);
        }
        used = 0; overflow = false;
        if (!query) continue;
        if (requested_baud) {
            xSemaphoreTake(lock,portMAX_DELAY); pending_baud = requested_baud; xSemaphoreGive(lock);
            vTaskDelay(pdMS_TO_TICKS(500));
        }
        hardware_status_t s; hardware_get_status(&s);
        xSemaphoreTake(lock,portMAX_DELAY);
        size_t count = raw_count, start = (raw_next + USB_RAW_BYTES - count) % USB_RAW_BYTES;
        int baud = active_baud;
        for (size_t i = 0; i < count; ++i) sample[i] = raw_ring[(start+i) % USB_RAW_BYTES];
        xSemaphoreGive(lock);
        for (size_t i = 0; i < count; ++i) { hex[2*i] = digits[sample[i] >> 4]; hex[2*i+1] = digits[sample[i] & 15]; }
        hex[2*count] = 0;
        // USB is physically local. Never expose credentials or enable actuators here.
        // Formatting/output runs on a separate low-priority task, outside the sensor/control locks.
        printf("LIDAR_STATUS {\"firmware\":\"%s\",\"uptime_ms\":%lld,\"rx_gpio\":%d,\"baud\":%d,"
               "\"configured\":%s,\"rx_bytes\":%lu,\"rx_age_ms\":%lld,\"packets\":%lu,\"crc_errors\":%lu,"
               "\"format_errors\":%lu,\"uart_errors\":%lu,\"scan_seq\":%lu,\"scan_age_ms\":%lld,"
               "\"fresh\":%s,\"complete\":%s,\"points\":%u,\"valid_points\":%u,\"nearest_mm\":%u,"
               "\"speed_raw\":%u,\"offset_cdeg\":%d,\"motion_configured\":%s,\"driver_enabled\":%s}\n",
               esp_app_get_description()->version,(long long)now_ms(),RASTAG_LIDAR_RX_GPIO,baud,
               s.lidar_configured?"true":"false",(unsigned long)s.rx_bytes,(long long)s.rx_age_ms,
               (unsigned long)s.packets,(unsigned long)s.crc_errors,(unsigned long)s.format_errors,(unsigned long)s.uart_errors,
               (unsigned long)s.scan_sequence,(long long)s.scan_age_ms,s.lidar_fresh?"true":"false",
               s.scan_complete?"true":"false",s.points,s.valid_points,s.nearest_mm,s.speed_raw,s.offset_cdeg,
               s.motion_configured?"true":"false",s.driver_enabled?"true":"false");
        if (raw_query) printf("LIDAR_RAW %s\n",hex);
        else if (!hardware_get_scan(&usb_scan)) printf("LIDAR_SCAN_UNAVAILABLE\n");
        else {
            // Snapshot the published scan once. The sensor keeps filling the next scan independently.
            printf("LIDAR_SCAN_BEGIN {\"sequence\":%lu,\"count\":%u,\"protocol_version\":%u,"
                   "\"angle_unit\":\"0.01_deg\",\"distance_unit\":\"mm\",\"age_ms\":%lld}\n",
                   (unsigned long)usb_scan.sequence,usb_scan.count,usb_scan.protocol_version,
                   (long long)(now_ms()-usb_scan.ended_ms));
            for (unsigned begin = 0; begin < usb_scan.count; begin += 16) {
                size_t pos = 0; point_line[pos++] = '[';
                unsigned end = begin+16 < usb_scan.count ? begin+16 : usb_scan.count;
                for (unsigned i = begin; i < end; ++i) {
                    const lidar_point_t *p = &usb_scan.points[i];
                    int written = snprintf(point_line+pos,sizeof(point_line)-pos,"%s[%u,%u,%u]",
                                           i==begin?"":",",p->angle_cdeg,p->distance_mm,p->quality);
                    configASSERT(written > 0 && (size_t)written < sizeof(point_line)-pos);
                    pos += (size_t)written;
                }
                point_line[pos++] = ']'; point_line[pos] = 0;
                printf("LIDAR_POINTS %s\n",point_line);
            }
            printf("LIDAR_SCAN_END %lu\n",(unsigned long)usb_scan.sequence);
        }
        fflush(stdout);
    }
}
void hardware_usb_diagnostics_start(void) {
    if (!uart_is_driver_installed(UART_NUM_0)) {
        const uart_config_t config = {.baud_rate=115200,.data_bits=UART_DATA_8_BITS,.parity=UART_PARITY_DISABLE,
            .stop_bits=UART_STOP_BITS_1,.flow_ctrl=UART_HW_FLOWCTRL_DISABLE,.source_clk=UART_SCLK_DEFAULT};
        ESP_ERROR_CHECK(uart_param_config(UART_NUM_0,&config));
        ESP_ERROR_CHECK(uart_driver_install(UART_NUM_0,2048,0,0,NULL,0));
    }
    configASSERT(xTaskCreatePinnedToCore(usb_diagnostics_task,"rastag-usb-read",4096,NULL,1,NULL,0) == pdPASS);
}
void hardware_load_motion_profile(void) {
    nvs_handle_t store; rover_profile_t p; size_t size=sizeof(p);
    if (nvs_open("rastag_motion",NVS_READONLY,&store)!=ESP_OK) return;
    esp_err_t rc=nvs_get_blob(store,"profile",&p,&size); nvs_close(store);
    if (rc!=ESP_OK || size!=sizeof(p) || !rover_profile_valid(&p)) return;
    xSemaphoreTake(lock,portMAX_DELAY); rover_init(&motion,&p); xSemaphoreGive(lock);
}
void hardware_link_renew(int64_t until) { xSemaphoreTake(lock,portMAX_DELAY); if (!maintenance) link_until=until; xSemaphoreGive(lock); }
bool hardware_maintenance_begin(void) {
    xSemaphoreTake(lock,portMAX_DELAY);
    bool allowed=!maintenance && motion.mode==ROVER_OFF && !motion.output.relay;
    if (allowed) { maintenance=true; link_until=0; rover_stop(&motion,ROVER_MAINTENANCE); apply_output(); }
    xSemaphoreGive(lock); return allowed;
}
void hardware_maintenance_end(void) {
    xSemaphoreTake(lock,portMAX_DELAY); link_until=0; maintenance=false; xSemaphoreGive(lock);
}
void hardware_link_lost(void) {
    xSemaphoreTake(lock,portMAX_DELAY); link_until=0; rover_stop(&motion,ROVER_LINK_LOST); apply_output(); xSemaphoreGive(lock);
}
void hardware_new_session(void) {
    xSemaphoreTake(lock,portMAX_DELAY); rover_new_session(&motion); apply_output(); xSemaphoreGive(lock);
}
bool hardware_command(const char *operation,uint32_t seq,int16_t left,int16_t right,int64_t deadline) {
    (void)left; (void)right; (void)deadline;
    // Legacy two-wheel drive semantics cannot bypass the calibrated ARG direction table.
    if (strcmp(operation,"stop")) return false;
    xSemaphoreTake(lock,portMAX_DELAY); rover_stop(&motion,ROVER_STOPPED);
    motion.requested=ROVER_NONE; if (seq>motion.sequence) motion.sequence=seq;
    apply_output(); xSemaphoreGive(lock); return true;
}
bool hardware_rover_drive(uint32_t seq,uint32_t run,unsigned direction,unsigned intensity,int64_t deadline) {
    xSemaphoreTake(lock,portMAX_DELAY); rover_inputs_t in=inputs(now_ms());
    bool ok=!maintenance && !output_fault && rover_drive(&motion,seq,run,(rover_direction_t)direction,intensity,deadline,now_ms(),&in);
    if (!ok) apply_output();
    xSemaphoreGive(lock); return ok;
}
bool hardware_rover_jog(uint32_t seq,uint32_t run,unsigned left,unsigned right,int64_t deadline) {
    xSemaphoreTake(lock,portMAX_DELAY); rover_inputs_t in=inputs(now_ms());
    bool ok=!maintenance && !output_fault && rover_jog(&motion,seq,run,left,right,deadline,now_ms(),in.link_live);
    if (!ok) apply_output();
    xSemaphoreGive(lock); return ok;
}
bool hardware_rover_profile(uint32_t seq,const rover_profile_t *p) {
    if (!rover_profile_valid(p)) return false;
    xSemaphoreTake(lock,portMAX_DELAY);
    if (maintenance || output_fault || motion.mode!=ROVER_OFF || !seq || seq<=motion.sequence) { xSemaphoreGive(lock); return false; }
    maintenance=true; motion.sequence=seq; cut_power(); xSemaphoreGive(lock);
    nvs_handle_t store; esp_err_t rc=nvs_open("rastag_motion",NVS_READWRITE,&store);
    if (rc==ESP_OK) { rc=nvs_set_blob(store,"profile",p,sizeof(*p)); if (rc==ESP_OK) rc=nvs_commit(store); nvs_close(store); }
    xSemaphoreTake(lock,portMAX_DELAY);
    if (rc==ESP_OK) { rover_init(&motion,p); motion.sequence=seq; rover_scan_clearance(&scan,p->front_cdeg,clearance); }
    maintenance=false; apply_output(); xSemaphoreGive(lock); return rc==ESP_OK;
}
void hardware_get_motion(rover_control_t *out,rover_inputs_t *in) {
    xSemaphoreTake(lock,portMAX_DELAY); *out=motion; *in=inputs(now_ms()); xSemaphoreGive(lock);
}
void hardware_get_status(hardware_status_t *out) {
    xSemaphoreTake(lock,portMAX_DELAY); int64_t now=now_ms(); rover_inputs_t in=inputs(now);
    *out=(hardware_status_t){.lidar_configured=lidar_configured,.lidar_fresh=in.scan_fresh,
        .lidar_verified=rover_profile_valid(&motion.profile),.scan_complete=scan.complete,.all_returns_valid=scan.all_returns_valid,
        .motion_configured=rover_profile_valid(&motion.profile),.armed=motion.mode==ROVER_HOMING||motion.mode==ROVER_DRIVING,
        .driver_enabled=motion.output.relay,.estop_released=!output_fault,
        .scan_sequence=scan.sequence,.command_sequence=motion.sequence,.packets=packets,.crc_errors=crc_errors,.format_errors=format_errors,.uart_errors=uart_errors,
        .rx_bytes=rx_bytes,.speed_raw=last_speed,.offset_cdeg=last_offset,.rx_age_ms=last_rx_ms>=0?now-last_rx_ms:-1,
        .points=scan.count,.valid_points=scan.valid_count,.nearest_mm=scan.nearest_mm,
        .scan_age_ms=scan.complete&&now>=scan.ended_ms?now-scan.ended_ms:-1,.motion_reason=motion.reason};
    xSemaphoreGive(lock);
}
bool hardware_get_scan(lidar_scan_t *out) {
    xSemaphoreTake(lock,portMAX_DELAY); rover_inputs_t in=inputs(now_ms());
    bool valid=in.scan_fresh; if (valid) *out=scan; xSemaphoreGive(lock); return valid;
}
