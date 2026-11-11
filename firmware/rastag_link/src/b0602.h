#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// 3irobotix 16-sector AD packets: B0602 v0/CRC16 and Delta-2A v1/Sum16.
// The attached unit's v1 stream was captured over GPIO27 at 230400 baud.
#define B0602_TEETH 16
#define B0602_POINTS_PER_PACKET 64
#define B0602_MAX_FRAME (15 + 3 * B0602_POINTS_PER_PACKET)
#define B0602_MAX_SCAN (B0602_TEETH * B0602_POINTS_PER_PACKET)
#define B0602_FRAME_TIMEOUT_MS 100
#define B0602_SCAN_TIMEOUT_MS 300

typedef struct { uint16_t angle_cdeg, distance_mm; uint8_t quality; } lidar_point_t;
typedef struct {
    uint16_t start_cdeg;
    int16_t offset_cdeg;
    uint8_t speed_raw, count, command, protocol_version;
    lidar_point_t points[B0602_POINTS_PER_PACKET];
} b0602_packet_t;
typedef enum { B0602_WAIT, B0602_SCAN_PACKET, B0602_SENSOR_FAULT, B0602_REJECTED } b0602_result_t;
typedef struct {
    uint8_t bytes[B0602_MAX_FRAME];
    size_t used;
    int64_t started_ms;
    uint32_t valid_packets, crc_errors, format_errors, timeouts;
} b0602_parser_t;
typedef struct {
    uint32_t sequence;
    int64_t started_ms, ended_ms;
    uint16_t count, valid_count, nearest_mm;
    bool complete, all_returns_valid;
    uint8_t protocol_version;
    lidar_point_t points[B0602_MAX_SCAN];
} lidar_scan_t;
typedef struct { lidar_scan_t building; uint8_t next_tooth; bool active; uint32_t sequence; } b0602_scan_builder_t;

uint16_t b0602_crc(const uint8_t *data, size_t size);
uint16_t lidar_sum16(const uint8_t *data, size_t size);
bool b0602_point_valid(const lidar_point_t *point, uint8_t protocol_version);
b0602_result_t b0602_feed(b0602_parser_t *parser, uint8_t byte, int64_t now, b0602_packet_t *out);
void b0602_reset_stream(b0602_parser_t *parser);
void b0602_scan_reset(b0602_scan_builder_t *builder);
bool b0602_scan_add(b0602_scan_builder_t *builder, const b0602_packet_t *packet, int64_t now, lidar_scan_t *out);
