#pragma once

// Owner confirmed B0602 TX wired to GPIO27 on 2026-11-10. Receive only.
// ARG Rover wiring confirmed by owner. Direction presets still require calibration.
#define RASTAG_LIDAR_RX_GPIO 27
#define RASTAG_LIDAR_BAUD 230400
#define RASTAG_LIDAR_STALE_MS 300
#define RASTAG_CONTROL_PERIOD_MS 10
#define RASTAG_LIDAR_PROFILE "3irobotix_delta2a_sum16_v1"
#define RASTAG_ARG_RELAY_GPIO 4
#define RASTAG_ARG_SERVO_1_GPIO 18
#define RASTAG_ARG_SERVO_2_GPIO 19
#define RASTAG_ARG_OPTO_3_GPIO 23
#define RASTAG_ARG_OPTO_4_GPIO 13
#define RASTAG_ARG_DAC_MV 2630

// GPIO25/26 are DAC channels 1/2. GPIO4 relay is ACTIVE LOW, safe level HIGH.
// No external emergency-stop input or wheel encoder has been confirmed.
