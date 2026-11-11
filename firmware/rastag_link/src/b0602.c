#include "b0602.h"
#include <string.h>
#include <limits.h>

static uint16_t be16(const uint8_t *p) { return (uint16_t)((uint16_t)p[0] << 8) | p[1]; }
uint16_t b0602_crc(const uint8_t *data, size_t size) {
    // CRC-16/MODBUS polynomial, initial FFFF. B0602 transmits the result HIGH byte first.
    uint16_t crc = 0xffff;
    for (size_t i = 0; i < size; ++i) {
        crc ^= data[i];
        for (unsigned bit = 0; bit < 8; ++bit) crc = (uint16_t)((crc >> 1) ^ ((crc & 1) ? 0xa001 : 0));
    }
    return crc;
}
void b0602_reset_stream(b0602_parser_t *p) { p->used = 0; p->started_ms = 0; }
uint16_t lidar_sum16(const uint8_t *data, size_t size) {
    uint16_t sum = 0;
    for (size_t i = 0; i < size; ++i) sum = (uint16_t)(sum + data[i]);
    return sum;
}
static void drop_byte(b0602_parser_t *p) {
    if (p->used) { --p->used; memmove(p->bytes, p->bytes + 1, p->used); }
}
b0602_result_t b0602_feed(b0602_parser_t *p, uint8_t byte, int64_t now, b0602_packet_t *out) {
    bool rejected = false;
    if (p->used && (now < p->started_ms || now - p->started_ms >= B0602_FRAME_TIMEOUT_MS)) {
        b0602_reset_stream(p); ++p->timeouts; rejected = true;
    }
    if (p->used == sizeof(p->bytes)) { drop_byte(p); ++p->format_errors; rejected = true; }
    if (!p->used) p->started_ms = now;
    p->bytes[p->used++] = byte;
    while (p->used) {
        if (p->bytes[0] != 0xaa) { drop_byte(p); continue; }
        if (p->used < 3) break;
        // Length counts header + payload, excluding the final two CRC bytes.
        size_t length = be16(p->bytes + 1), total = length + 2;
        if (length < 9 || total > sizeof(p->bytes)) {
            drop_byte(p); ++p->format_errors; rejected = true; continue;
        }
        if (p->used < 8) break;
        uint16_t payload = be16(p->bytes + 6);
        uint8_t command = p->bytes[5];
        bool measurement = command == 0xad;
        bool health = command == 0xab || command == 0xae;
        if (p->bytes[3] > 1 || p->bytes[4] != 0x61 || payload + 8u != length
                || (!measurement && !health) || (health && payload != 1)
                || (measurement && (payload < 8 || (payload - 5) % 3 != 0))) {
            drop_byte(p); ++p->format_errors; rejected = true; continue;
        }
        if (p->used < total) break;
        // Select the documented check by protocol version; never try a weaker fallback.
        uint16_t expected = p->bytes[3] == 0 ? b0602_crc(p->bytes,length) : lidar_sum16(p->bytes,length);
        if (expected != be16(p->bytes + length)) {
            drop_byte(p); ++p->crc_errors; rejected = true; continue;
        }
        memset(out, 0, sizeof(*out)); out->command = command; out->protocol_version = p->bytes[3];
        if (measurement) {
            out->speed_raw = p->bytes[8];
            out->offset_cdeg = (int16_t)be16(p->bytes + 9);
            out->start_cdeg = be16(p->bytes + 11);
            out->count = (uint8_t)((payload - 5) / 3);
            if (!out->speed_raw || out->start_cdeg >= 36000 || out->start_cdeg % 2250 != 0
                    || !out->count || out->count > B0602_POINTS_PER_PACKET) {
                drop_byte(p); ++p->format_errors; rejected = true; continue;
            }
            for (unsigned i = 0; i < out->count; ++i) {
                const uint8_t *point = p->bytes + 13 + 3 * i;
                out->points[i].quality = point[0];
                out->points[i].distance_mm = be16(point + 1) / 4;
                // B0602 V1.0 manual: true angle includes the signed factory zero offset.
                // Keep raw start_cdeg unchanged for the 16-sector scan assembler.
                // Delta-2A v1 calls offset a diagnostic field and explicitly says not to apply it.
                int32_t offset = out->protocol_version == 0 ? out->offset_cdeg : 0;
                int32_t angle = (int32_t)out->start_cdeg + offset + (int32_t)(2250u * i / out->count);
                angle %= 36000;
                if (angle < 0) angle += 36000;
                out->points[i].angle_cdeg = (uint16_t)angle;
            }
            // Base-frame rotation is still a separate, measured installation calibration.
        }
        p->used -= total; memmove(p->bytes, p->bytes + total, p->used); p->started_ms = now;
        ++p->valid_packets;
        return measurement ? B0602_SCAN_PACKET : B0602_SENSOR_FAULT;
    }
    return rejected ? B0602_REJECTED : B0602_WAIT;
}
void b0602_scan_reset(b0602_scan_builder_t *b) { b->active = false; b->next_tooth = 0; }
bool b0602_point_valid(const lidar_point_t *p, uint8_t version) {
    return version == 1 ? p->distance_mm >= 150 && p->distance_mm <= 8000
        : version == 0 && p->quality > 0 && p->distance_mm >= 130 && p->distance_mm <= 6000;
}
bool b0602_scan_add(b0602_scan_builder_t *b, const b0602_packet_t *p, int64_t now, lidar_scan_t *out) {
    if (p->command != 0xad || p->protocol_version > 1 || !p->count || p->count > B0602_POINTS_PER_PACKET
            || p->start_cdeg >= 36000 || p->start_cdeg % 2250 != 0) { b0602_scan_reset(b); return false; }
    unsigned tooth = p->start_cdeg / 2250;
    if (b->active && (now < b->building.started_ms || now - b->building.started_ms >= B0602_SCAN_TIMEOUT_MS)) b0602_scan_reset(b);
    if (tooth == 0) {
        memset(&b->building, 0, sizeof(b->building));
        b->building.started_ms = now; b->building.nearest_mm = UINT16_MAX;
        b->building.all_returns_valid = true; b->active = true; b->next_tooth = 0;
        b->building.protocol_version = p->protocol_version;
    }
    if (!b->active || tooth != b->next_tooth || b->building.protocol_version != p->protocol_version
            || b->building.count + p->count > B0602_MAX_SCAN) {
        b0602_scan_reset(b); return false;
    }
    for (unsigned i = 0; i < p->count; ++i) {
        lidar_point_t value = p->points[i];
        b->building.points[b->building.count++] = value;
        // Invalid/no-return points are UNKNOWN, never proof of a clear path.
        // Delta-2A quality is a diagnostic value; validity is based on its 0.15-8 m range.
        bool valid = b0602_point_valid(&value,p->protocol_version);
        if (valid) {
            ++b->building.valid_count;
            if (value.distance_mm < b->building.nearest_mm) b->building.nearest_mm = value.distance_mm;
        } else b->building.all_returns_valid = false;
    }
    if (++b->next_tooth != B0602_TEETH) return false;
    b->building.complete = true; b->building.ended_ms = now;
    b->building.sequence = ++b->sequence; *out = b->building;
    b0602_scan_reset(b); return true;
}
