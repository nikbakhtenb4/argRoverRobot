#include "lidar_wire.h"
#include <string.h>

static void le(uint8_t *out, uint64_t value, unsigned bytes) {
    for (unsigned i = 0; i < bytes; ++i) { out[i] = (uint8_t)value; value >>= 8; }
}
static int hex(char c) { return c >= '0' && c <= '9' ? c-'0' : c >= 'a' && c <= 'f' ? c-'a'+10 : -1; }
uint32_t lidar_wire_crc32(const uint8_t *bytes, size_t count) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < count; ++i) {
        crc ^= bytes[i];
        for (unsigned bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ ((crc & 1u) ? 0xedb88320u : 0u);
    }
    return crc ^ 0xffffffffu;
}
size_t lidar_wire_encode(const lidar_scan_t *scan, const char *boot, int64_t sent,
                         uint8_t *out, size_t capacity) {
    if (!scan || !out || !boot || strlen(boot) != 32 || !scan->complete || !scan->count
        || scan->count > B0602_MAX_SCAN || scan->protocol_version > 1 || scan->started_ms < 0
        || scan->ended_ms < scan->started_ms || scan->ended_ms-scan->started_ms > B0602_SCAN_TIMEOUT_MS
        || sent < scan->ended_ms || sent-scan->ended_ms >= B0602_SCAN_TIMEOUT_MS) return 0;
    size_t length = LIDAR_WIRE_HEADER + 6u*scan->count + 4u;
    if (capacity < length) return 0;
    memset(out,0,length);
    for (unsigned i = 0; i < 16; ++i) {
        int a = hex(boot[2*i]), b = hex(boot[2*i+1]);
        if (a < 0 || b < 0) return 0;
        out[48+i] = (uint8_t)((a << 4) | b);
    }
    memcpy(out,"RLS2",4); le(out+4,2,2); le(out+6,LIDAR_WIRE_HEADER,2); le(out+8,length,4);
    le(out+12,scan->sequence,4); le(out+16,scan->count,2); le(out+18,6,2);
    le(out+24,(uint64_t)scan->started_ms,8); le(out+32,(uint64_t)scan->ended_ms,8); le(out+40,(uint64_t)sent,8);
    bool all_valid = true;
    for (unsigned i = 0; i < scan->count; ++i) {
        const lidar_point_t *p = &scan->points[i];
        if (p->angle_cdeg >= 36000) return 0;
        uint8_t *record = out+LIDAR_WIRE_HEADER+6*i;
        le(record,p->angle_cdeg,2); le(record+2,p->distance_mm,2); record[4] = p->quality;
        record[5] = b0602_point_valid(p,scan->protocol_version) ? 1 : 0;
        all_valid = all_valid && record[5];
    }
    le(out+20,1u | (all_valid ? 2u : 0u),4);
    le(out+length-4,lidar_wire_crc32(out,length-4),4);
    return length;
}
