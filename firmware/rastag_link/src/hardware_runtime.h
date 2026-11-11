#pragma once
#include "b0602.h"
#include "arg_rover.h"

typedef struct {
    bool lidar_configured, lidar_fresh, lidar_verified, scan_complete, all_returns_valid;
    bool motion_configured, armed, driver_enabled, estop_released;
    uint32_t scan_sequence, command_sequence, packets, crc_errors, format_errors, uart_errors;
    uint32_t rx_bytes;
    uint8_t speed_raw;
    int16_t offset_cdeg;
    uint16_t points, valid_points, nearest_mm;
    int64_t scan_age_ms, rx_age_ms;
    rover_reason_t motion_reason;
} hardware_status_t;

void hardware_init(void);
// Start after provisioning releases UART0. LIDAR? reads status; bounded RX baud probes
// are allowed only while motion is disabled. No sensor TX or actuator commands exist.
void hardware_usb_diagnostics_start(void);
void hardware_link_renew(int64_t valid_until_ms);
void hardware_link_lost(void);
void hardware_new_session(void);
// Exclusive OTA maintenance gate. Never accepts a moving/armed controller.
bool hardware_maintenance_begin(void);
void hardware_maintenance_end(void);
bool hardware_command(const char *operation, uint32_t sequence, int16_t left, int16_t right, int64_t deadline_ms);
void hardware_get_status(hardware_status_t *out);
bool hardware_get_scan(lidar_scan_t *out);
void hardware_load_motion_profile(void);
void hardware_get_motion(rover_control_t *out, rover_inputs_t *inputs);
bool hardware_rover_drive(uint32_t seq, uint32_t run, unsigned direction, unsigned intensity, int64_t deadline);
bool hardware_rover_jog(uint32_t seq, uint32_t run, unsigned left, unsigned right, int64_t deadline);
bool hardware_rover_profile(uint32_t seq, const rover_profile_t *profile);
