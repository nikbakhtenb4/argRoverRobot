#include "arg_rover.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static unsigned checks;
#define CHECK(v) do { ++checks; if (!(v)) { fprintf(stderr,"FAIL %d: %s\n",__LINE__,#v); exit(1); } } while(0)
static rover_profile_t verified(void) {
    rover_profile_t p; rover_profile_default(&p); p.verified=1;
    const uint16_t pairs[4][2]={{10200,10700},{9800,10300},{9800,10700},{10200,10300}};
    memcpy(p.target_cdeg,pairs,sizeof(pairs)); return p;
}
static rover_inputs_t clear(void) {
    rover_inputs_t in={.link_live=true,.scan_fresh=true};
    for (unsigned i=0;i<4;++i) in.clearance[i]=(rover_clearance_t){true,2000};
    return in;
}
int main(void) {
    rover_profile_t p; rover_profile_default(&p);
    CHECK(!rover_profile_valid(&p)); p.verified=1; CHECK(!rover_profile_valid(&p)); // GitHub's identical presets cannot drive.
    p=verified(); CHECK(rover_profile_valid(&p));
    CHECK(rover_pulse_us(10000)==1611); CHECK(rover_pulse_us(10500)==1667); CHECK(rover_dac_code(2630)==203);
    rover_control_t r; rover_init(&r,&p); rover_inputs_t in=clear();
    CHECK(!r.output.relay && !r.output.pwm && !r.output.opto_mask);
    CHECK(rover_drive(&r,1,1,ROVER_FORWARD,50,250,0,&in));
    for (int now=0;now<=1800;now+=10) {
        if (now && now%100==0) CHECK(rover_drive(&r,(uint32_t)(now/100+1),1,ROVER_FORWARD,50,now+250,now,&in));
        rover_tick(&r,now,&in);
        CHECK(now<1500?!r.output.relay:r.output.relay);
        CHECK(r.output.pulse_us[0]>=1611 && r.output.pulse_us[0]<=1623);
    }
    CHECK(r.output.opto_mask==15 && r.decision==ROVER_FORWARD);
    in.clearance[0].nearest_mm=200; rover_tick(&r,1810,&in);
    CHECK(r.reason==ROVER_OBSTACLE && !r.output.relay && !r.output.pwm && !r.output.opto_mask);
    in=clear(); CHECK(!rover_drive(&r,20,1,ROVER_FORWARD,50,2070,1820,&in)); // Old held command cannot resume after obstacle.
    CHECK(rover_drive(&r,21,2,ROVER_FORWARD,50,2080,1830,&in));
    CHECK(!rover_drive(&r,21,2,ROVER_FORWARD,50,2080,1830,&in)); CHECK(!r.output.relay);
    rover_new_session(&r); CHECK(r.sequence==0 && r.run==0);
    rover_init(&r,&p);
    CHECK(rover_jog(&r,1,1,11000,10500,1250,1000,true));
    rover_tick(&r,1000,&in); CHECK(!r.output.relay); // no powered hoverboard during servo calibration.
    for (int n=1010;n<=1250;n+=10) rover_tick(&r,n,&in);
    CHECK(r.reason==ROVER_COMMAND_EXPIRED && !r.output.pwm && !r.output.relay);
    CHECK(!rover_jog(&r,2,1,11000,10500,1600,1400,true)); // Expired run is latched.
    rover_tick(&r,1400,&in);
    CHECK(rover_jog(&r,3,2,11000,10500,1660,1410,true));
    in.link_live=false; rover_tick(&r,1420,&in); CHECK(r.reason==ROVER_LINK_LOST && !r.output.pwm);
    in=clear(); rover_init(&r,&p); CHECK(rover_drive(&r,1,1,ROVER_LEFT,100,250,0,&in));
    rover_tick(&r,0,&in); rover_tick(&r,120,&in); CHECK(r.reason==ROVER_LOOP_STALLED);
    rover_new_session(&r); in.scan_fresh=false;
    CHECK(!rover_drive(&r,1,1,ROVER_FORWARD,100,250,0,&in)); CHECK(r.reason==ROVER_LIDAR_UNKNOWN);
    p=verified(); p.target_cdeg[1][0]=p.target_cdeg[0][0];p.target_cdeg[1][1]=p.target_cdeg[0][1]; CHECK(!rover_profile_valid(&p));
    p=verified(); p.target_cdeg[0][0]=12001; CHECK(!rover_profile_valid(&p));
    p=verified(); p.stop_mm=299; CHECK(!rover_profile_valid(&p));
    lidar_scan_t scan={.complete=true,.count=180,.protocol_version=1};
    for (unsigned i=0;i<180;++i) scan.points[i]=(lidar_point_t){.angle_cdeg=i*200,.distance_mm=2000,.quality=150};
    rover_clearance_t c[4]; rover_scan_clearance(&scan,0,c);
    for (unsigned i=0;i<4;++i) CHECK(c[i].observed && c[i].nearest_mm==2000);
    scan.points[0].distance_mm=150; rover_scan_clearance(&scan,0,c);
    CHECK(c[0].nearest_mm==150 && c[1].nearest_mm==2000 && c[2].nearest_mm==150);
    scan.points[90].distance_mm=0; rover_scan_clearance(&scan,0,c);
    CHECK(c[0].observed && !c[1].observed && !c[2].observed && !c[3].observed);
    rover_scan_clearance(&scan,18000,c); CHECK(!c[0].observed && c[1].nearest_mm==150);
    scan.complete=false; rover_scan_clearance(&scan,0,c); CHECK(!c[0].observed && !c[0].nearest_mm);
    printf("PASS: %u ARG Rover assertions; relay off on boot, expiry, obstacle, replay and calibration\n",checks);
}
