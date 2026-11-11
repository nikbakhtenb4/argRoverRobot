#pragma once
#include <stdbool.h>
#include <stdint.h>

#define MOTION_COMMAND_MAX_MS 250
#define MOTION_CONTROL_MAX_GAP_MS 100
typedef enum {
    MOTION_DISABLED, MOTION_DISARMED, MOTION_ARMED,
    MOTION_STOP_REQUEST, MOTION_LINK_LOST, MOTION_ESTOP, MOTION_LIDAR_UNKNOWN,
    MOTION_OBSTACLE, MOTION_EXPIRED, MOTION_BAD_COMMAND, MOTION_LOOP_STALLED, MOTION_OUTPUT_ERROR
} motion_reason_t;
typedef struct {
    bool enabled, calibration_verified, protocol_verified, inhibit_verified;
    uint16_t neutral_us[2], minimum_us[2], maximum_us[2];
    int8_t direction[2];
    uint16_t slew_us_per_second, stop_distance_mm, slow_distance_mm;
} motion_config_t;
typedef struct {
    bool link_live, estop_released, scan_fresh, scan_complete, all_returns_valid;
    uint16_t nearest_mm;
} motion_inputs_t;
typedef struct { uint16_t pulse_us[2]; bool driver_enable; } motion_output_t;
typedef struct {
    motion_config_t config;
    bool configured, armed, commanded;
    motion_reason_t reason;
    uint32_t sequence;
    int64_t deadline_ms, settle_until_ms, last_tick_ms;
    int16_t effort[2];
    motion_output_t output;
} motion_control_t;

bool motion_config_valid(const motion_config_t *config);
void motion_init(motion_control_t *m, const motion_config_t *config);
void motion_stop(motion_control_t *m, motion_reason_t reason);
void motion_new_session(motion_control_t *m);
bool motion_arm(motion_control_t *m, uint32_t sequence, int64_t now, const motion_inputs_t *inputs);
bool motion_drive(motion_control_t *m, uint32_t sequence, int16_t left, int16_t right,
                  int64_t deadline_ms, int64_t now, const motion_inputs_t *inputs);
void motion_tick(motion_control_t *m, int64_t now, const motion_inputs_t *inputs);
const char *motion_reason_name(motion_reason_t reason);
