#include "motion_control.h"
#include <string.h>

bool motion_config_valid(const motion_config_t *c) {
    if (!c->enabled || !c->calibration_verified || !c->protocol_verified || !c->inhibit_verified
            || c->slew_us_per_second < 100 || c->slew_us_per_second > 1000
            || c->stop_distance_mm < 300 || c->stop_distance_mm > 3000
            || c->slow_distance_mm < c->stop_distance_mm + 100 || c->slow_distance_mm > 5000) return false;
    for (unsigned i = 0; i < 2; ++i) {
        if (c->minimum_us[i] < 500 || c->maximum_us[i] > 2500 || c->minimum_us[i] >= c->neutral_us[i]
                || c->neutral_us[i] >= c->maximum_us[i] || c->neutral_us[i] - c->minimum_us[i] > 250
                || c->maximum_us[i] - c->neutral_us[i] > 250 || (c->direction[i] != 1 && c->direction[i] != -1)) return false;
    }
    return true;
}
void motion_stop(motion_control_t *m, motion_reason_t reason) {
    m->armed = false; m->commanded = false; m->deadline_ms = 0;
    m->effort[0] = m->effort[1] = 0; m->output.driver_enable = false;
    m->output.pulse_us[0] = m->configured ? m->config.neutral_us[0] : 0;
    m->output.pulse_us[1] = m->configured ? m->config.neutral_us[1] : 0;
    m->reason = m->configured ? reason : MOTION_DISABLED;
}
void motion_init(motion_control_t *m, const motion_config_t *c) {
    memset(m, 0, sizeof(*m)); m->config = *c; m->configured = motion_config_valid(c); m->last_tick_ms = -1;
    motion_stop(m, MOTION_DISARMED);
}
void motion_new_session(motion_control_t *m) { motion_stop(m, MOTION_DISARMED); m->sequence = 0; }
static motion_reason_t input_fault(const motion_control_t *m, const motion_inputs_t *in) {
    if (!in->estop_released) return MOTION_ESTOP;
    if (!in->link_live) return MOTION_LINK_LOST;
    if (!in->scan_fresh || !in->scan_complete || !in->all_returns_valid) return MOTION_LIDAR_UNKNOWN;
    if (in->nearest_mm <= m->config.stop_distance_mm) return MOTION_OBSTACLE;
    return MOTION_ARMED;
}
bool motion_arm(motion_control_t *m, uint32_t sequence, int64_t now, const motion_inputs_t *in) {
    if (!m->configured) return false;
    motion_reason_t fault = input_fault(m, in);
    if (!sequence || sequence <= m->sequence || m->armed || now < 0) { motion_stop(m, MOTION_BAD_COMMAND); return false; }
    if (fault != MOTION_ARMED) { motion_stop(m, fault); return false; }
    motion_stop(m, MOTION_DISARMED);
    m->sequence = sequence; m->armed = true; m->reason = MOTION_ARMED;
    // Arming only establishes neutral. A later fresh drive request is required for driver enable.
    m->settle_until_ms = now + 300; m->deadline_ms = now + 1000;
    return true;
}
bool motion_drive(motion_control_t *m, uint32_t sequence, int16_t left, int16_t right,
                  int64_t deadline, int64_t now, const motion_inputs_t *in) {
    if (!m->configured) return false;
    motion_reason_t fault = input_fault(m, in);
    if (fault != MOTION_ARMED) { motion_stop(m, fault); return false; }
    if (!m->armed || !sequence || sequence <= m->sequence || left < -1000 || left > 1000 || right < -1000 || right > 1000
            || now < m->settle_until_ms || now >= m->deadline_ms || deadline <= now || deadline - now > MOTION_COMMAND_MAX_MS) {
        motion_stop(m, MOTION_BAD_COMMAND); return false;
    }
    m->sequence = sequence; m->effort[0] = left; m->effort[1] = right;
    m->deadline_ms = deadline; m->commanded = true; return true;
}
void motion_tick(motion_control_t *m, int64_t now, const motion_inputs_t *in) {
    int64_t elapsed = m->last_tick_ms < 0 ? 0 : now - m->last_tick_ms; m->last_tick_ms = now;
    if (!m->configured || !m->armed) return;
    if (elapsed < 0 || elapsed > MOTION_CONTROL_MAX_GAP_MS) { motion_stop(m, MOTION_LOOP_STALLED); return; }
    motion_reason_t fault = input_fault(m, in);
    if (fault != MOTION_ARMED) { motion_stop(m, fault); return; }
    if (now >= m->deadline_ms) { motion_stop(m, MOTION_EXPIRED); return; }
    if (!m->commanded || now < m->settle_until_ms) return;
    int scale = in->nearest_mm >= m->config.slow_distance_mm ? 1000 :
        (int)(in->nearest_mm - m->config.stop_distance_mm) * 1000 / (m->config.slow_distance_mm - m->config.stop_distance_mm);
    int step = (int)(m->config.slew_us_per_second * elapsed / 1000);
    for (unsigned i = 0; i < 2; ++i) {
        int effort = m->effort[i] * m->config.direction[i] * scale / 1000;
        int span = effort >= 0 ? m->config.maximum_us[i] - m->config.neutral_us[i] : m->config.neutral_us[i] - m->config.minimum_us[i];
        int target = m->config.neutral_us[i] + effort * span / 1000;
        int current = m->output.pulse_us[i];
        if (target > current) current += target - current > step ? step : target - current;
        else current -= current - target > step ? step : current - target;
        m->output.pulse_us[i] = (uint16_t)current;
    }
    m->output.driver_enable = m->effort[0] != 0 || m->effort[1] != 0;
}
const char *motion_reason_name(motion_reason_t r) {
    static const char *names[] = {"hardware_not_configured","disarmed","armed","stop_requested","link_lost","emergency_stop",
        "lidar_unknown","obstacle","command_expired","invalid_command","control_loop_stalled","output_error"};
    return (unsigned)r < sizeof(names)/sizeof(names[0]) ? names[r] : "unknown";
}
