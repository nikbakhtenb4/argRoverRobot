// ARG Rover actuator model: two tilt servos, four optos, active-low power relay.
// No default direction is invented: verified direction presets are required.
#include "arg_rover.h"
#include <string.h>
#include <limits.h>

const uint16_t ROVER_HOME_CDEG[2] = {10000,10500};
void rover_profile_default(rover_profile_t *p) {
    memset(p,0,sizeof(*p)); p->version=ROVER_PROFILE_VERSION; p->stop_mm=400;
    for (unsigned d=0; d<4; ++d) memcpy(p->target_cdeg[d],ROVER_HOME_CDEG,sizeof(ROVER_HOME_CDEG));
}
bool rover_profile_valid(const rover_profile_t *p) {
    if (!p || p->version!=ROVER_PROFILE_VERSION || p->verified!=1 || p->front_cdeg>=36000 ||
        p->stop_mm<300 || p->stop_mm>3000) return false;
    for (unsigned d=0; d<4; ++d) {
        for (unsigned i=0; i<2; ++i)
            if (p->target_cdeg[d][i]<ROVER_MIN_CDEG || p->target_cdeg[d][i]>ROVER_MAX_CDEG) return false;
        if (!memcmp(p->target_cdeg[d],ROVER_HOME_CDEG,sizeof(ROVER_HOME_CDEG))) return false;
        for (unsigned earlier=0; earlier<d; ++earlier)
            if (!memcmp(p->target_cdeg[d],p->target_cdeg[earlier],sizeof(ROVER_HOME_CDEG))) return false;
    }
    return true;
}
uint16_t rover_pulse_us(uint16_t cdeg) { return (uint16_t)(500u+((uint32_t)cdeg*2000u+9000u)/18000u); }
uint8_t rover_dac_code(uint16_t mv) { return (uint8_t)(((mv>3000?3000:mv)*255u+1650u)/3300u); }
void rover_stop(rover_control_t *r, rover_reason_t why) {
    r->mode=ROVER_OFF; r->decision=ROVER_NONE; r->deadline_ms=0;
    r->reason=why; memset(&r->output,0,sizeof(r->output));
    // Power/optos/PWM are removed immediately. Home is established with power OFF
    // before any subsequent drive; stopping never leaves the previous tilt powered.
}
void rover_init(rover_control_t *r, const rover_profile_t *p) {
    memset(r,0,sizeof(*r)); r->profile=*p; r->last_tick_ms=-1;
    memcpy(r->current_cdeg,ROVER_HOME_CDEG,sizeof(ROVER_HOME_CDEG));
    memcpy(r->target_cdeg,ROVER_HOME_CDEG,sizeof(ROVER_HOME_CDEG));
    rover_stop(r,rover_profile_valid(p)?ROVER_STOPPED:ROVER_NEEDS_CALIBRATION);
}
void rover_new_session(rover_control_t *r) { rover_stop(r,ROVER_STOPPED); r->sequence=0; r->run=0; r->requested=ROVER_NONE; }
static bool fresh(rover_control_t *r, uint32_t seq, int64_t deadline, int64_t now) {
    if (!seq || seq<=r->sequence || now<0 || deadline<=now || deadline-now>ROVER_COMMAND_MS) {
        rover_stop(r,ROVER_BAD_COMMAND); return false;
    }
    r->sequence=seq; return true;
}
static rover_reason_t obstacle(const rover_control_t *r, const rover_inputs_t *in) {
    if (!in->link_live) return ROVER_LINK_LOST;
    if (!in->scan_fresh || r->requested<ROVER_FORWARD || r->requested>ROVER_RIGHT) return ROVER_LIDAR_UNKNOWN;
    const rover_clearance_t *c=&in->clearance[r->requested-1];
    // A known close obstacle stops even if another part of the sector is unknown.
    if (c->nearest_mm && c->nearest_mm<=r->profile.stop_mm) return ROVER_OBSTACLE;
    if (!c->observed || !c->nearest_mm) return ROVER_LIDAR_UNKNOWN;
    return ROVER_MOVING;
}
bool rover_drive(rover_control_t *r, uint32_t seq, uint32_t run, rover_direction_t direction, unsigned intensity,
                 int64_t deadline, int64_t now, const rover_inputs_t *in) {
    if (!fresh(r,seq,deadline,now)) return false;
    if (!run || (r->mode!=ROVER_OFF && run!=r->run)) { rover_stop(r,ROVER_BAD_COMMAND); return false; }
    if (r->mode==ROVER_OFF && run==r->run) return false;
    r->run=run;
    if (direction<ROVER_FORWARD || direction>ROVER_RIGHT || !intensity || intensity>100) {
        rover_stop(r,ROVER_BAD_COMMAND); return false;
    }
    rover_direction_t previous=r->requested; r->requested=direction;
    if (!rover_profile_valid(&r->profile)) { rover_stop(r,ROVER_NEEDS_CALIBRATION); return false; }
    rover_reason_t why=obstacle(r,in);
    if (why!=ROVER_MOVING) { rover_stop(r,why); return false; }
    if (r->mode==ROVER_CALIBRATING || (r->mode!=ROVER_OFF && previous!=direction) ||
        (r->mode!=ROVER_OFF && now>=r->deadline_ms)) { rover_stop(r,ROVER_BAD_COMMAND); return false; }
    if (r->mode==ROVER_OFF) {
        r->mode=ROVER_HOMING; r->reason=ROVER_PREPARING; r->home_until_ms=now+ROVER_HOME_MS;
        memcpy(r->current_cdeg,ROVER_HOME_CDEG,sizeof(ROVER_HOME_CDEG));
        r->output.pwm=true;
    }
    r->deadline_ms=deadline; r->intensity=(uint16_t)intensity;
    return true;
}
bool rover_jog(rover_control_t *r, uint32_t seq, uint32_t run, unsigned left, unsigned right,
               int64_t deadline, int64_t now, bool link_live) {
    if (!fresh(r,seq,deadline,now)) return false;
    if (!run || (r->mode!=ROVER_OFF && run!=r->run)) { rover_stop(r,ROVER_BAD_COMMAND); return false; }
    if (r->mode==ROVER_OFF && run==r->run) return false;
    r->run=run;
    if (!link_live) { rover_stop(r,ROVER_LINK_LOST); return false; }
    if (left<ROVER_MIN_CDEG || left>ROVER_MAX_CDEG || right<ROVER_MIN_CDEG || right>ROVER_MAX_CDEG ||
        (r->mode!=ROVER_OFF && (r->mode!=ROVER_CALIBRATING || now>=r->deadline_ms))) { rover_stop(r,ROVER_BAD_COMMAND); return false; }
    if (r->mode==ROVER_OFF) memcpy(r->current_cdeg,ROVER_HOME_CDEG,sizeof(ROVER_HOME_CDEG));
    r->mode=ROVER_CALIBRATING; r->reason=ROVER_JOGGING; r->requested=r->decision=ROVER_NONE;
    r->deadline_ms=deadline; r->target_cdeg[0]=(uint16_t)left; r->target_cdeg[1]=(uint16_t)right;
    r->output.pwm=true; r->output.relay=false; r->output.opto_mask=0;
    return true;
}
void rover_tick(rover_control_t *r, int64_t now, const rover_inputs_t *in) {
    int64_t dt=r->last_tick_ms<0?0:now-r->last_tick_ms; r->last_tick_ms=now;
    if (r->mode==ROVER_OFF) return;
    if (dt<0 || dt>100) { rover_stop(r,ROVER_LOOP_STALLED); return; }
    if (!in->link_live) { rover_stop(r,ROVER_LINK_LOST); return; }
    if (now>=r->deadline_ms) { rover_stop(r,ROVER_COMMAND_EXPIRED); return; }
    if (r->mode!=ROVER_CALIBRATING) {
        rover_reason_t why=obstacle(r,in);
        if (why!=ROVER_MOVING) { rover_stop(r,why); return; }
        if (r->mode==ROVER_HOMING && now>=r->home_until_ms) r->mode=ROVER_DRIVING;
        for (unsigned i=0; i<2; ++i) {
            int delta=r->profile.target_cdeg[r->requested-1][i]-ROVER_HOME_CDEG[i];
            unsigned clearance=in->clearance[r->requested-1].nearest_mm-r->profile.stop_mm;
            unsigned scale=clearance>=400?100:clearance/4;
            r->target_cdeg[i]=r->mode==ROVER_HOMING?ROVER_HOME_CDEG[i]:
                (uint16_t)(ROVER_HOME_CDEG[i]+delta*(int)r->intensity*(int)scale/10000);
        }
        r->decision=r->mode==ROVER_DRIVING?r->requested:ROVER_NONE;
        r->reason=r->mode==ROVER_DRIVING?ROVER_MOVING:ROVER_PREPARING;
        r->output.relay=r->mode==ROVER_DRIVING;
        r->output.opto_mask=r->output.relay?0x0f:0;
    }
    // 10 degrees/second, 50 Hz servo PWM, bounded 80..120 degrees.
    int step=(int)dt;
    for (unsigned i=0; i<2; ++i) {
        int current=r->current_cdeg[i], target=r->target_cdeg[i];
        if (target>current) current+=target-current>step?step:target-current;
        else current-=current-target>step?step:current-target;
        r->current_cdeg[i]=(uint16_t)current; r->output.pulse_us[i]=rover_pulse_us((uint16_t)current);
    }
}
void rover_scan_clearance(const lidar_scan_t *scan, uint16_t front, rover_clearance_t out[4]) {
    memset(out,0,4*sizeof(*out));
    if (!scan || !scan->complete || scan->count>B0602_MAX_SCAN || front>=36000) return;
    // One valid return in every 2-degree bin of the swept area is required.
    // Forward/reverse use 90-degree sectors; turning checks the entire footprint.
    bool seen[180]={0}; uint16_t nearest[180];
    for (unsigned b=0; b<180; ++b) nearest[b]=UINT16_MAX;
    for (unsigned i=0; i<scan->count; ++i) {
        const lidar_point_t *p=&scan->points[i];
        if (!b0602_point_valid(p,scan->protocol_version) || p->angle_cdeg>=36000) continue;
        unsigned relative=(p->angle_cdeg+36000u-front)%36000u, bin=relative/200;
        seen[bin]=true; if (p->distance_mm<nearest[bin]) nearest[bin]=p->distance_mm;
    }
    for (unsigned d=0; d<4; ++d) {
        bool complete=true; uint16_t min=UINT16_MAX;
        for (unsigned b=0; b<180; ++b) {
            bool needed=d>=2 || (d==0?(b<23 || b>=157):(b>=67 && b<113));
            if (!needed) continue;
            if (!seen[b]) complete=false;
            if (nearest[b]<min) min=nearest[b];
        }
        out[d]=(rover_clearance_t){.observed=complete,.nearest_mm=min==UINT16_MAX?0:min};
    }
}
const char *rover_mode_name(rover_mode_t m) {
    static const char *names[]={"off","homing","driving","calibrating"}; return (unsigned)m<4?names[m]:"off";
}
const char *rover_reason_name(rover_reason_t r) {
    static const char *names[]={"stopped","calibration_required","preparing_home","moving","servo_calibration",
        "link_lost","command_expired","obstacle","lidar_unknown","invalid_command","control_loop_stalled","output_error","ota_maintenance"};
    return (unsigned)r<sizeof(names)/sizeof(names[0])?names[r]:"unknown";
}
const char *rover_direction_name(rover_direction_t d) {
    static const char *names[]={"stop","forward","back","left","right"}; return (unsigned)d<5?names[d]:"stop";
}
