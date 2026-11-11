#pragma once
#include "b0602.h"

// RLS2: fixed 64-byte LE header, six bytes/point, trailing IEEE CRC32.
#define LIDAR_WIRE_HEADER 64u
#define LIDAR_WIRE_MAX (LIDAR_WIRE_HEADER + 6u * B0602_MAX_SCAN + 4u)
uint32_t lidar_wire_crc32(const uint8_t *bytes, size_t count);
size_t lidar_wire_encode(const lidar_scan_t *scan, const char *boot_hex, int64_t sent_ms,
                         uint8_t *out, size_t capacity);
