// Authenticated ESP link and locally timed LiDAR/motion runtime. Hardware defaults are inert.
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include <sys/time.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "driver/uart.h"
#include "esp_event.h"
#include "esp_wifi.h"
#include "esp_mac.h"
#include "esp_netif.h"
#include "esp_netif_sntp.h"
#include "esp_https_server.h"
#include "esp_timer.h"
#include "esp_random.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "cJSON.h"
#include "mdns.h"
#include "link_protocol.h"
#include "hardware_runtime.h"
#include "hardware_config.h"
#include "ota_update.h"
#include "esp_app_desc.h"
#include "lidar_wire.h"
#include "sdkconfig.h"
#ifndef CONFIG_MBEDTLS_HAVE_TIME_DATE
#error "Certificate validity checks are required for the controller link"
#endif

#define CONFIG_LIMIT 12288
#define WIFI_READY BIT0
static EventGroupHandle_t events;
static link_session_t session;
static char device_id[40], hostname[64], boot_id[33];
static cJSON *provision;
static int64_t now_ms(void) { return esp_timer_get_time() / 1000; }
static const char *string(cJSON *o, const char *key) {
    cJSON *v = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsString(v) ? v->valuestring : NULL;
}
static bool fields(cJSON *o, const char **names, int count) {
    if (!cJSON_IsObject(o) || cJSON_GetArraySize(o) != count) return false;
    for (int i = 0; i < count; i++) {
        int matches = 0;
        cJSON *v;
        cJSON_ArrayForEach(v, o) if (v->string && strcmp(v->string, names[i]) == 0) matches++;
        if (matches != 1) return false;
    }
    return true;
}
static void random_token(char *out) {
    unsigned char bytes[16]; esp_fill_random(bytes, sizeof(bytes));
    for (int i = 0; i < 16; i++) sprintf(out + 2*i, "%02x", bytes[i]);
}
static esp_err_t reply(httpd_req_t *req, const char *status, const char *body) {
    httpd_resp_set_status(req, status);
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    return httpd_resp_send(req, body, HTTPD_RESP_USE_STRLEN);
}
static esp_err_t ack(httpd_req_t *req) {
    char json[400];
    hardware_status_t hw; hardware_get_status(&hw);
    snprintf(json, sizeof(json), "{\"protocol\":1,\"device\":\"%s\",\"boot\":\"%s\",\"session\":\"%s\",\"seq\":%lu,\"lease_ms\":7000,\"live\":%s,\"motion_enabled\":%s,\"uptime_ms\":%lld}",
        device_id, boot_id, session.token, (unsigned long)session.seq,
        link_live(&session, now_ms()) ? "true" : "false", hw.driver_enabled ? "true" : "false", (long long)now_ms());
    return reply(req, "200 OK", json);
}
static cJSON *body(httpd_req_t *req) {
    if (req->content_len < 2 || req->content_len > 1024) return NULL;
    char raw[1025]; int n = 0;
    while (n < req->content_len) {
        int got = httpd_req_recv(req, raw+n, req->content_len-n);
        if (got <= 0) return NULL;
        n += got;
    }
    raw[n] = 0;
    if (memchr(raw, 0, n) != NULL) return NULL;
    // All command schemas are flat objects. Bound nesting before cJSON uses stack recursion.
    bool quoted = false, escaped = false; unsigned depth = 0;
    for (int i = 0; i < n; ++i) {
        char ch = raw[i];
        if (quoted) {
            if (escaped) escaped = false;
            else if (ch == '\\') escaped = true;
            else if (ch == '"') quoted = false;
        } else if (ch == '"') quoted = true;
        else if (ch == '[' || ch == ']') return NULL;
        else if (ch == '{') { if (++depth > 1) return NULL; }
        else if (ch == '}') { if (!depth) return NULL; --depth; }
    }
    if (quoted || depth) return NULL;
    return cJSON_ParseWithOpts(raw, NULL, true);
}
static esp_err_t new_session(httpd_req_t *req) {
    cJSON *o = body(req);
    const char *names[] = {"protocol"};
    cJSON *v = cJSON_GetObjectItemCaseSensitive(o, "protocol");
    bool valid = fields(o, names, 1) && cJSON_IsNumber(v) && v->valuedouble == 1;
    cJSON_Delete(o);
    if (!valid) return reply(req, "400 Bad Request", "{\"error\":\"invalid_request\"}");
    char token[33]; random_token(token);
    hardware_new_session();
    link_new_session(&session, token, now_ms());
    hardware_link_renew(session.last_ms + LINK_LEASE_MS);
    return ack(req);
}
static esp_err_t heartbeat(httpd_req_t *req) {
    cJSON *o = body(req);
    const char *names[] = {"session", "seq"};
    cJSON *seq = cJSON_GetObjectItemCaseSensitive(o, "seq");
    bool valid = fields(o, names, 2) && cJSON_IsNumber(seq) && seq->valuedouble > 0
        && seq->valuedouble <= 4294967295.0 && seq->valuedouble == (uint32_t)seq->valuedouble;
    bool accepted = valid && link_heartbeat(&session, string(o, "session"), (uint32_t)seq->valuedouble, now_ms());
    cJSON_Delete(o);
    if (!valid) return reply(req, "400 Bad Request", "{\"error\":\"invalid_request\"}");
    if (!accepted) return reply(req, "409 Conflict", "{\"error\":\"stale_session_or_sequence\"}");
    hardware_link_renew(session.last_ms + LINK_LEASE_MS);
    return ack(req);
}
static esp_err_t status(httpd_req_t *req) { return ack(req); }
static esp_err_t hardware_state(httpd_req_t *req) {
    hardware_status_t s; hardware_get_status(&s);
    char raw[1500];
    snprintf(raw,sizeof(raw),
        "{\"protocol\":2,\"firmware\":\"%s\",\"boot\":\"%s\",\"uptime_ms\":%lld,"
        "\"lidar\":{\"configured\":%s,\"profile\":\"" RASTAG_LIDAR_PROFILE "\",\"hardware_verified\":%s,\"fresh\":%s,\"complete\":%s,"
        "\"all_returns_valid\":%s,\"scan_seq\":%lu,\"scan_age_ms\":%lld,\"points\":%u,\"valid_points\":%u,\"nearest_mm\":%u,"
        "\"packets\":%lu,\"crc_errors\":%lu,\"format_errors\":%lu,\"uart_errors\":%lu},"
        "\"motion\":{\"configured\":%s,\"armed\":%s,\"driver_enabled\":%s,\"stop_confirmed\":false,\"reason\":\"%s\","
        "\"command_seq\":%lu,\"command_max_ms\":250,\"control_period_ms\":10}}",
        esp_app_get_description()->version,boot_id,(long long)now_ms(),s.lidar_configured?"true":"false",s.lidar_verified?"true":"false",
        s.lidar_fresh?"true":"false",s.scan_complete?"true":"false",s.all_returns_valid?"true":"false",(unsigned long)s.scan_sequence,
        (long long)s.scan_age_ms,s.points,s.valid_points,s.nearest_mm,(unsigned long)s.packets,(unsigned long)s.crc_errors,
        (unsigned long)s.format_errors,(unsigned long)s.uart_errors,s.motion_configured?"true":"false",s.armed?"true":"false",
        s.driver_enabled?"true":"false",rover_reason_name(s.motion_reason),(unsigned long)s.command_sequence);
    return reply(req,"200 OK",raw);
}
static bool integer(cJSON *o, const char *key, int64_t low, int64_t high, int64_t *out) {
    cJSON *v = cJSON_GetObjectItemCaseSensitive(o,key);
    if (!cJSON_IsNumber(v) || !isfinite(v->valuedouble) || v->valuedouble < low || v->valuedouble > high) return false;
    int64_t value = (int64_t)v->valuedouble;
    if (v->valuedouble != value) return false;
    *out = value; return true;
}
static esp_err_t rover_state_reply(httpd_req_t *req,bool accepted,const char *http_status) {
    rover_control_t r; rover_inputs_t in; hardware_get_motion(&r,&in);
    cJSON *o=cJSON_CreateObject(); if (!o) return ESP_ERR_NO_MEM;
    cJSON_AddNumberToObject(o,"protocol",3); cJSON_AddStringToObject(o,"boot",boot_id);
    cJSON_AddNumberToObject(o,"uptime_ms",now_ms()); cJSON_AddBoolToObject(o,"accepted",accepted);
    cJSON_AddNumberToObject(o,"seq",r.sequence); cJSON_AddNumberToObject(o,"run",r.run);
    cJSON_AddBoolToObject(o,"configured",rover_profile_valid(&r.profile));
    cJSON_AddStringToObject(o,"mode",rover_mode_name(r.mode)); cJSON_AddStringToObject(o,"reason",rover_reason_name(r.reason));
    cJSON_AddStringToObject(o,"requested",rover_direction_name(r.requested));
    cJSON_AddStringToObject(o,"decision",rover_direction_name(r.decision));
    cJSON_AddBoolToObject(o,"relay",r.output.relay); cJSON_AddBoolToObject(o,"pwm",r.output.pwm);
    cJSON_AddNumberToObject(o,"optos",r.output.opto_mask); cJSON_AddBoolToObject(o,"stop_confirmed",false);
    cJSON_AddBoolToObject(o,"scan_fresh",in.scan_fresh); cJSON_AddBoolToObject(o,"link_live",in.link_live);
    cJSON_AddNumberToObject(o,"left_cdeg",r.current_cdeg[0]); cJSON_AddNumberToObject(o,"right_cdeg",r.current_cdeg[1]);
    cJSON_AddNumberToObject(o,"command_max_ms",ROVER_COMMAND_MS);
    cJSON *profile=cJSON_AddObjectToObject(o,"profile");
    const char *names[]={"f_left","f_right","b_left","b_right","l_left","l_right","r_left","r_right"};
    for (unsigned i=0;i<8;++i) cJSON_AddNumberToObject(profile,names[i],r.profile.target_cdeg[i/2][i%2]);
    cJSON_AddNumberToObject(profile,"front_cdeg",r.profile.front_cdeg); cJSON_AddNumberToObject(profile,"stop_mm",r.profile.stop_mm);
    cJSON_AddBoolToObject(profile,"verified",r.profile.verified==1);
    cJSON *clear=cJSON_AddArrayToObject(o,"clearance");
    for (unsigned i=0;i<4;++i) {
        cJSON *c=cJSON_CreateObject(); cJSON_AddBoolToObject(c,"observed",in.clearance[i].observed);
        cJSON_AddNumberToObject(c,"nearest_mm",in.clearance[i].nearest_mm); cJSON_AddItemToArray(clear,c);
    }
    char *raw=cJSON_PrintUnformatted(o); cJSON_Delete(o); if (!raw) return ESP_ERR_NO_MEM;
    esp_err_t rc=reply(req,http_status,raw); free(raw); return rc;
}
static esp_err_t rover_state(httpd_req_t *req) { return rover_state_reply(req,true,"200 OK"); }
static esp_err_t rover_request(httpd_req_t *req) {
    cJSON *o=body(req); const char *operation=string(o,"operation");
    bool stop=operation&&!strcmp(operation,"stop"), drive=operation&&!strcmp(operation,"drive");
    bool jog=operation&&!strcmp(operation,"jog"), configure=operation&&!strcmp(operation,"configure");
    const char *stop_fields[]={"boot","session","seq","operation"};
    const char *drive_fields[]={"boot","session","seq","operation","expires_at_ms","run","direction","intensity"};
    const char *jog_fields[]={"boot","session","seq","operation","expires_at_ms","run","left_cdeg","right_cdeg"};
    const char *config_fields[]={"boot","session","seq","operation","expires_at_ms","verified", "f_left","f_right","b_left","b_right","l_left","l_right","r_left","r_right","front_cdeg","stop_mm"};
    int64_t seq=0,deadline=0,run=0,a=0,b=0;
    bool valid=(stop||drive||jog||configure) && fields(o,stop?stop_fields:drive?drive_fields:jog?jog_fields:config_fields,stop?4:configure?16:8)
        && string(o,"boot") && string(o,"session") && integer(o,"seq",1,UINT32_MAX,&seq)
        && (stop || integer(o,"expires_at_ms",1,1000000000000LL,&deadline))
        && (!(drive||jog) || integer(o,"run",1,UINT32_MAX,&run));
    if (drive) valid=valid&&integer(o,"direction",1,4,&a)&&integer(o,"intensity",1,100,&b);
    if (jog) valid=valid&&integer(o,"left_cdeg",ROVER_MIN_CDEG,ROVER_MAX_CDEG,&a)&&integer(o,"right_cdeg",ROVER_MIN_CDEG,ROVER_MAX_CDEG,&b);
    rover_profile_t profile; rover_profile_default(&profile);
    if (configure) {
        valid=valid&&cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(o,"verified"));
        for (unsigned i=0;i<8 && valid;++i) { int64_t v=0; valid=integer(o,config_fields[6+i],ROVER_MIN_CDEG,ROVER_MAX_CDEG,&v); profile.target_cdeg[i/2][i%2]=(uint16_t)v; }
        valid=valid&&integer(o,"front_cdeg",0,35999,&a)&&integer(o,"stop_mm",300,3000,&b);
        profile.front_cdeg=(uint16_t)a; profile.stop_mm=(uint16_t)b; profile.verified=1;
        valid=valid&&rover_profile_valid(&profile);
    }
    int64_t now=now_ms();
    bool authorized=valid&&!strcmp(string(o,"boot"),boot_id)&&link_matches(&session,string(o,"session"))
        && (stop || (link_live(&session,now)&&deadline>now&&deadline-now<=(configure?2000:ROVER_COMMAND_MS)));
    cJSON_Delete(o);
    if (!valid) return reply(req,"400 Bad Request","{\"error\":\"invalid_motion_request\"}");
    if (!authorized) return reply(req,"409 Conflict","{\"error\":\"expired_motion_request\"}");
    bool accepted=stop?hardware_command("stop",(uint32_t)seq,0,0,0):
        drive?hardware_rover_drive((uint32_t)seq,(uint32_t)run,(unsigned)a,(unsigned)b,deadline):
        jog?hardware_rover_jog((uint32_t)seq,(uint32_t)run,(unsigned)a,(unsigned)b,deadline):
        hardware_rover_profile((uint32_t)seq,&profile);
    return rover_state_reply(req,accepted,accepted?"200 OK":"423 Locked");
}
static esp_err_t motion_request(httpd_req_t *req) {
    const char *operation = (const char *)req->user_ctx;
    bool stop = !strcmp(operation,"stop"), drive = !strcmp(operation,"drive");
    cJSON *o = body(req);
    const char *stop_fields[] = {"boot","session","seq"};
    const char *arm_fields[] = {"boot","session","seq","expires_at_ms"};
    const char *drive_fields[] = {"boot","session","seq","expires_at_ms","left","right"};
    int64_t seq = 0, left = 0, right = 0, deadline = 0;
    bool valid = fields(o,stop ? stop_fields : drive ? drive_fields : arm_fields,stop ? 3 : drive ? 6 : 4)
        && string(o,"boot") && string(o,"session") && integer(o,"seq",1,4294967295LL,&seq)
        && (stop || integer(o,"expires_at_ms",1,1000000000000LL,&deadline))
        && (!drive || (integer(o,"left",-1000,1000,&left) && integer(o,"right",-1000,1000,&right)));
    bool authorized = valid && !strcmp(string(o,"boot"),boot_id) && link_matches(&session,string(o,"session"))
        && (stop || link_live(&session,now_ms()));
    cJSON_Delete(o);
    if (!valid) return reply(req,"400 Bad Request","{\"error\":\"invalid_request\"}");
    if (!authorized) return reply(req,"409 Conflict","{\"error\":\"wrong_or_expired_session\"}");
    hardware_status_t s; hardware_get_status(&s);
    if (!stop && !s.motion_configured) return reply(req,"423 Locked","{\"error\":\"hardware_not_configured\",\"accepted\":false}");
    bool accepted = hardware_command(operation,(uint32_t)seq,(int16_t)left,(int16_t)right,deadline);
    if (!accepted) return reply(req,"409 Conflict","{\"error\":\"motion_interlock_or_stale_command\",\"accepted\":false}");
    return reply(req,"200 OK","{\"accepted\":true,\"stop_confirmed\":false}");
}
static void little(uint8_t *out, uint64_t value, unsigned bytes) {
    for (unsigned i = 0; i < bytes; ++i) { out[i] = (uint8_t)value; value >>= 8; }
}
static esp_err_t lidar_scan(httpd_req_t *req) {
    lidar_scan_t *scan = malloc(sizeof(*scan));
    if (!scan) return reply(req,"503 Service Unavailable","{\"error\":\"resource_limit\"}");
    if (!hardware_get_scan(scan)) { free(scan); return reply(req,"503 Service Unavailable","{\"error\":\"lidar_not_ready\"}"); }
    size_t length = 32 + 6 * scan->count;
    uint8_t *raw = calloc(1,length);
    if (!raw) { free(scan); return reply(req,"503 Service Unavailable","{\"error\":\"resource_limit\"}"); }
    memcpy(raw,"RLS1",4); little(raw+4,1,2); little(raw+6,scan->count,2); little(raw+8,scan->sequence,4);
    little(raw+12,1 | (scan->all_returns_valid ? 2 : 0),4); little(raw+16,scan->started_ms,8); little(raw+24,scan->ended_ms,8);
    for (unsigned i = 0; i < scan->count; ++i) {
        little(raw+32+6*i,scan->points[i].angle_cdeg,2); little(raw+34+6*i,scan->points[i].distance_mm,2); raw[36+6*i] = scan->points[i].quality;
    }
    free(scan); httpd_resp_set_type(req,"application/octet-stream");
    httpd_resp_set_hdr(req,"Cache-Control","no-store"); httpd_resp_set_hdr(req,"X-Rastag-Boot",boot_id);
    esp_err_t result = httpd_resp_send(req,(const char *)raw,length); free(raw); return result;
}
// A complete immutable scan is copied under the runtime lock; TLS writes never hold it.
static esp_err_t lidar_frame(httpd_req_t *req) {
    lidar_scan_t *scan = malloc(sizeof(*scan));
    uint8_t *raw = malloc(LIDAR_WIRE_MAX);
    size_t length = 0;
    if (scan && raw && hardware_get_scan(scan)) length = lidar_wire_encode(scan,boot_id,now_ms(),raw,LIDAR_WIRE_MAX);
    free(scan);
    if (!length) { free(raw); return reply(req,"503 Service Unavailable","{\"error\":\"lidar_not_ready\"}"); }
    httpd_resp_set_type(req,"application/octet-stream"); httpd_resp_set_hdr(req,"Cache-Control","no-store");
    esp_err_t result = httpd_resp_send(req,(const char *)raw,length); free(raw); return result;
}
static void wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data) {
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) esp_wifi_connect();
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        hardware_link_lost();
        xEventGroupClearBits(events, WIFI_READY);
        printf("LINK_WIFI_RETRY %u\n", (unsigned)((wifi_event_sta_disconnected_t *)data)->reason);
        esp_wifi_connect(); // Wi-Fi driver performs the scan/reconnect asynchronously.
    }
    if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *ip = data;
        printf("LINK_IP " IPSTR "\n", IP2STR(&ip->ip_info.ip));
        xEventGroupSetBits(events, WIFI_READY);
    }
}
static bool valid_config(cJSON *o) {
    const char *names[] = {"device", "hostname", "ssid", "password", "server_cert", "server_key", "client_ca", "epoch"};
    if (!fields(o, names, 8)) return false;
    for (int i = 0; i < 7; i++) if (!string(o, names[i])) return false;
    const char *ssid = string(o, "ssid"), *pass = string(o, "password");
    cJSON *epoch = cJSON_GetObjectItemCaseSensitive(o, "epoch");
    return strcmp(string(o, "device"), device_id) == 0 && strcmp(string(o, "hostname"), hostname) == 0
        && strlen(ssid) > 0 && strlen(ssid) <= 32 && strlen(pass) >= 8 && strlen(pass) <= 63
        && cJSON_IsNumber(epoch) && epoch->valuedouble >= 1767225600 && epoch->valuedouble < 4102444800.0
        && strlen(string(o, "server_cert")) < 4096 && strlen(string(o, "server_key")) < 4096
        && strlen(string(o, "client_ca")) < 4096;
}
static void provision_usb(void) {
    nvs_handle_t store;
    ESP_ERROR_CHECK(nvs_open("rastag_link", NVS_READWRITE, &store));
    size_t size = 0;
    if (nvs_get_str(store, "pair", NULL, &size) == ESP_OK && size <= CONFIG_LIMIT) {
        char *raw = calloc(1, size);
        ESP_ERROR_CHECK(nvs_get_str(store, "pair", raw, &size));
        provision = cJSON_ParseWithOpts(raw, NULL, true);
        memset(raw, 0, size); free(raw);
        if (!valid_config(provision)) { cJSON_Delete(provision); provision = NULL; }
    }
    if (!provision) {
        const uart_config_t uart = {.baud_rate=115200, .data_bits=UART_DATA_8_BITS, .parity=UART_PARITY_DISABLE,
            .stop_bits=UART_STOP_BITS_1, .flow_ctrl=UART_HW_FLOWCTRL_DISABLE, .source_clk=UART_SCLK_DEFAULT};
        ESP_ERROR_CHECK(uart_param_config(UART_NUM_0, &uart));
        ESP_ERROR_CHECK(uart_driver_install(UART_NUM_0, 16384, 0, 0, NULL, 0));
        char *raw = calloc(1, CONFIG_LIMIT); size_t used = 0; bool overflow = false;
        int64_t announced = -5000;
        while (!provision) {
            if (now_ms() - announced > 3000) { printf("LINK_UNPAIRED %s\n", device_id); announced = now_ms(); }
            unsigned char c;
            if (uart_read_bytes(UART_NUM_0, &c, 1, pdMS_TO_TICKS(100)) != 1) continue;
            if (c == '\n') {
                raw[used] = 0;
                cJSON *candidate = overflow ? NULL : cJSON_ParseWithOpts(raw, NULL, true);
                if (valid_config(candidate)) {
                    ESP_ERROR_CHECK(nvs_set_str(store, "pair", raw)); ESP_ERROR_CHECK(nvs_commit(store));
                    provision = candidate; printf("LINK_PROVISIONED %s\n", device_id);
                } else { cJSON_Delete(candidate); printf("LINK_REJECTED\n"); }
                memset(raw, 0, CONFIG_LIMIT); used = 0; overflow = false;
            } else if (used < CONFIG_LIMIT-1) raw[used++] = c;
            else overflow = true;
        }
        free(raw);
        // Keep the console UART clock/driver alive for bounded status diagnostics.
    }
    nvs_close(store);
}
void app_main(void) {
    ota_boot_watchdog_start();
    hardware_init();
    // Do not erase NVS automatically on an error: fail closed and preserve pairing.
    ESP_ERROR_CHECK(nvs_flash_init());
    hardware_load_motion_profile();
    uint8_t mac[6]; ESP_ERROR_CHECK(esp_read_mac(mac, ESP_MAC_WIFI_STA));
    snprintf(device_id, sizeof(device_id), "rastag-esp-%02x%02x%02x%02x%02x%02x", mac[0],mac[1],mac[2],mac[3],mac[4],mac[5]);
    snprintf(hostname, sizeof(hostname), "%s.local", device_id);
    printf("LINK_BOOT %s\n", device_id);
    provision_usb();
    hardware_usb_diagnostics_start();
    struct timeval tv = {.tv_sec=(time_t)cJSON_GetObjectItemCaseSensitive(provision, "epoch")->valuedouble};
    settimeofday(&tv, NULL); // USB trusted provisioning time is a lower bound, never disable TLS validation.
    events = xEventGroupCreate();
    ESP_ERROR_CHECK(esp_netif_init()); ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_t *net = esp_netif_create_default_wifi_sta();
    ESP_ERROR_CHECK(esp_netif_set_hostname(net, device_id));
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_event, NULL));
    wifi_config_t wifi = {0};
    memcpy(wifi.sta.ssid, string(provision,"ssid"), strlen(string(provision,"ssid")));
    memcpy(wifi.sta.password, string(provision,"password"), strlen(string(provision,"password")));
    wifi.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    wifi.sta.pmf_cfg.capable = true;
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA)); ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi));
    memset(&wifi, 0, sizeof(wifi));
    ESP_ERROR_CHECK(esp_wifi_start()); ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    xEventGroupWaitBits(events, WIFI_READY, pdFALSE, pdTRUE, portMAX_DELAY);
    esp_sntp_config_t time_config = ESP_NETIF_SNTP_DEFAULT_CONFIG_MULTIPLE(2, ESP_SNTP_SERVER_LIST("time.cloudflare.com", "pool.ntp.org"));
    ESP_ERROR_CHECK(esp_netif_sntp_init(&time_config));
    // Wait for current time on every boot; a stale provisioning epoch cannot authorize expired peers.
    while (esp_netif_sntp_sync_wait(pdMS_TO_TICKS(10000)) != ESP_OK) printf("LINK_WAITING_TIME\n");
    // No backwards clock update from a LAN time source may undo the trusted USB floor.
    uint64_t floor = (uint64_t)cJSON_GetObjectItemCaseSensitive(provision, "epoch")->valuedouble;
    nvs_handle_t clock_store; ESP_ERROR_CHECK(nvs_open("rastag_link", NVS_READWRITE, &clock_store));
    uint64_t saved_floor = 0; nvs_get_u64(clock_store, "time_floor", &saved_floor);
    if (saved_floor > floor) floor = saved_floor;
    time_t synced = time(NULL);
    if ((uint64_t)synced + 60 < floor) {
        printf("LINK_TIME_REJECTED\n");
        nvs_close(clock_store);
        return; // Pairing remains intact; HTTPS never starts with a rolled-back clock.
    }
    if ((uint64_t)synced > floor) floor = synced;
    ESP_ERROR_CHECK(nvs_set_u64(clock_store, "time_floor", floor)); ESP_ERROR_CHECK(nvs_commit(clock_store));
    nvs_close(clock_store);
    esp_netif_sntp_deinit();
    random_token(boot_id);
    httpd_ssl_config_t tls = HTTPD_SSL_CONFIG_DEFAULT();
    tls.port_secure = 8443;
    tls.httpd.max_open_sockets = 3; // Android link + LiDAR + one paired maintenance client.
    tls.httpd.lru_purge_enable = true; // An idle Android socket must not block paired maintenance.
    tls.httpd.max_uri_handlers = 14;
    tls.httpd.stack_size = 8192;
    tls.httpd.recv_wait_timeout = 3; tls.httpd.send_wait_timeout = 3;
    tls.servercert = (const uint8_t *)string(provision,"server_cert"); tls.servercert_len = strlen((const char *)tls.servercert)+1;
    tls.prvtkey_pem = (const uint8_t *)string(provision,"server_key"); tls.prvtkey_len = strlen((const char *)tls.prvtkey_pem)+1;
    tls.cacert_pem = (const uint8_t *)string(provision,"client_ca"); tls.cacert_len = strlen((const char *)tls.cacert_pem)+1;
    httpd_handle_t server = NULL;
    ESP_ERROR_CHECK(httpd_ssl_start(&server, &tls));
    // HTTPS has copied its key material; discard the provisioning JSON (including Wi-Fi password).
    cJSON *secret;
    cJSON_ArrayForEach(secret, provision) {
        if (cJSON_IsString(secret) && secret->valuestring) memset(secret->valuestring, 0, strlen(secret->valuestring));
    }
    cJSON_Delete(provision); provision = NULL;
    httpd_uri_t routes[] = {
        {.uri="/v1/session", .method=HTTP_POST, .handler=new_session},
        {.uri="/v1/heartbeat", .method=HTTP_POST, .handler=heartbeat},
        {.uri="/v1/status", .method=HTTP_GET, .handler=status},
        {.uri="/v2/state", .method=HTTP_GET, .handler=hardware_state},
        {.uri="/v3/motion", .method=HTTP_GET, .handler=rover_state},
        {.uri="/v3/motion", .method=HTTP_POST, .handler=rover_request},
        {.uri="/v2/lidar/scan", .method=HTTP_GET, .handler=lidar_scan},
        {.uri="/v2/lidar/frame", .method=HTTP_GET, .handler=lidar_frame},
        {.uri="/v2/motion/arm", .method=HTTP_POST, .handler=motion_request, .user_ctx="arm"},
        {.uri="/v2/motion/drive", .method=HTTP_POST, .handler=motion_request, .user_ctx="drive"},
        {.uri="/v2/motion/stop", .method=HTTP_POST, .handler=motion_request, .user_ctx="stop"}
    };
    for (unsigned i = 0; i < sizeof(routes)/sizeof(routes[0]); i++) ESP_ERROR_CHECK(httpd_register_uri_handler(server, &routes[i]));
    ota_register(server, device_id, boot_id);
    ESP_ERROR_CHECK(mdns_init()); ESP_ERROR_CHECK(mdns_hostname_set(device_id));
    ESP_ERROR_CHECK(mdns_service_add(device_id, "_rastaglink", "_tcp", 8443, NULL, 0));
    ota_boot_confirm_ready();
    printf("LINK_READY %s motion_disarmed\n", device_id);
}
