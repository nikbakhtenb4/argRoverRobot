#pragma once
#include "motion_control.h"
// Regression fixture for the retired generic controller, never linked into firmware.
#define RASTAG_SERVO_LEFT_GPIO (-1)
#define RASTAG_SERVO_RIGHT_GPIO (-1)
static const motion_config_t RASTAG_MOTION_CONFIG = {
    .enabled=false,.calibration_verified=false,.protocol_verified=false,.inhibit_verified=false,
    .neutral_us={0,0},.minimum_us={0,0},.maximum_us={0,0},.direction={1,1},
    .slew_us_per_second=200,.stop_distance_mm=0,.slow_distance_mm=0
};
