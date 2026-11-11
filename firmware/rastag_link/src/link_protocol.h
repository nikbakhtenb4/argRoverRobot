#pragma once
#include <stdbool.h>
#include <stdint.h>
#define LINK_LEASE_MS 7000
typedef struct {
    char token[33];
    uint32_t seq;
    int64_t last_ms;
    bool active;
} link_session_t;
void link_new_session(link_session_t *s, const char *token, int64_t now_ms);
bool link_live(const link_session_t *s, int64_t now_ms);
bool link_matches(const link_session_t *s, const char *token);
bool link_heartbeat(link_session_t *s, const char *token, uint32_t seq, int64_t now_ms);

