#include "link_protocol.h"
#include <string.h>
void link_new_session(link_session_t *s, const char *token, int64_t now_ms) {
    memset(s, 0, sizeof(*s));
    if (!token || strlen(token) != 32) return;
    memcpy(s->token, token, 33);
    s->last_ms = now_ms;
    s->active = true;
}
bool link_live(const link_session_t *s, int64_t now_ms) {
    return s->active && now_ms >= s->last_ms && now_ms - s->last_ms < LINK_LEASE_MS;
}
bool link_heartbeat(link_session_t *s, const char *token, uint32_t seq, int64_t now_ms) {
    if (!link_live(s, now_ms) || !link_matches(s,token) || seq == 0 || seq <= s->seq) return false;
    s->seq = seq;
    s->last_ms = now_ms;
    return true;
}
bool link_matches(const link_session_t *s, const char *token) {
    if (!s->active || !token || strlen(token) != 32) return false;
    unsigned diff = 0;
    for (int i = 0; i < 32; i++) diff |= (unsigned char)token[i] ^ (unsigned char)s->token[i];
    return diff == 0;
}

