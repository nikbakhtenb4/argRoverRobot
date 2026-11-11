#pragma once
#include <stdbool.h>
#include <stdint.h>
#include "b0602.h"

#define ROVER_COMMAND_MS 250
#define ROVER_HOME_MS 1500
#define ROVER_MIN_CDEG 8000
#define ROVER_MAX_CDEG 12000
#define ROVER_PROFILE_VERSION 1
typedef enum { ROVER_NONE, ROVER_FORWARD, ROVER_BACK, ROVER_LEFT, ROVER_RIGHT } rover_direction_t;
typedef enum { ROVER_OFF, ROVER_HOMING, ROVER_DRIVING, ROVER_CALIBRATING } rover_mode_t;
typedef enum {
    ROVER_STOPPED, ROVER_NEEDS_CALIBRATION, ROVER_PREPARING, ROVER_MOVING, ROVER_JOGGING,
    ROVER_LINK_LOST, ROVER_COMMAND_EXPIRED, ROVER_OBSTACLE, ROVER_LIDAR_UNKNOWN,
    ROVER_BAD_COMMAND, ROVER_LOOP_STALLED, ROVER_OUTPUT_ERROR, ROVER_MAINTENANCE
} rover_reason_t;
typedef struct {
    uint32_t version;
    uint16_t target_cdeg[4][2];
    uint16_t front_cdeg, stop_mm;
    uint8_t verified, reserved[3];
} rover_profile_t;
typedef struct { bool observed; uint16_t nearest_mm; } rover_clearance_t;
typedef struct { bool link_live, scan_fresh; rover_clearance_t clearance[4]; } rover_inputs_t;
typedef struct { bool pwm, relay; uint8_t opto_mask; uint16_t pulse_us[2]; } rover_output_t;
typedef struct {
    rover_profile_t profile;
    rover_mode_t mode;
    rover_reason_t reason;
    rover_direction_t requested, decision;
    uint32_t sequence, run;
    int64_t deadline_ms, home_until_ms, last_tick_ms;
    uint16_t current_cdeg[2], target_cdeg[2], intensity;
    rover_output_t output;
} rover_control_t;

extern const uint16_t ROVER_HOME_CDEG[2];
void rover_profile_default(rover_profile_t *p);
bool rover_profile_valid(const rover_profile_t *p);
uint16_t rover_pulse_us(uint16_t cdeg);
uint8_t rover_dac_code(uint16_t millivolts);
void rover_init(rover_control_t *r, const rover_profile_t *p);
void rover_stop(rover_control_t *r, rover_reason_t why);
void rover_new_session(rover_control_t *r);
bool rover_drive(rover_control_t *r, uint32_t seq, uint32_t run, rover_direction_t direction, unsigned intensity,
                 int64_t deadline, int64_t now, const rover_inputs_t *in);
bool rover_jog(rover_control_t *r, uint32_t seq, uint32_t run, unsigned left_cdeg, unsigned right_cdeg,
               int64_t deadline, int64_t now, bool link_live);
void rover_tick(rover_control_t *r, int64_t now, const rover_inputs_t *in);
void rover_scan_clearance(const lidar_scan_t *scan, uint16_t front_cdeg, rover_clearance_t out[4]);
const char *rover_mode_name(rover_mode_t mode);
const char *rover_reason_name(rover_reason_t why);
const char *rover_direction_name(rover_direction_t direction);
