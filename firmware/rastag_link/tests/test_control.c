#include "b0602.h"
#include "motion_control.h"
#include "link_protocol.h"
#include "hardware_config.h"
#include "legacy_motion_config.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "captured_delta2a.h"

static unsigned checks;
#define CHECK(x) do { ++checks; if (!(x)) { fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x); exit(1); } } while (0)
static b0602_result_t feed(b0602_parser_t *p,const uint8_t *data,size_t size,int64_t now,b0602_packet_t *out) {
    b0602_result_t result=B0602_WAIT;
    for(size_t i=0;i<size;++i){b0602_result_t r=b0602_feed(p,data[i],now,out);if(r!=B0602_WAIT)result=r;}
    return result;
}
static void crc_fill(uint8_t *packet,size_t size) {
    uint16_t crc=b0602_crc(packet,size-2);packet[size-2]=(uint8_t)(crc>>8);packet[size-1]=(uint8_t)crc;
}
static void parser_tests(void) {
    CHECK(b0602_crc((const uint8_t*)"123456789",9)==0x4b37);
    // Synthetic x2 packet. CRC bytes independently generated with the SDK's lookup tables.
    uint8_t golden[]={0xaa,0x00,0x13,0x00,0x61,0xad,0x00,0x0b,0x7c,0xff,0x9c,0x08,0xca,0xc8,0x0f,0xa0,0x01,0x1f,0x40,0xc8,0x04};
    CHECK(b0602_crc(golden,sizeof(golden)-2)==0xc804);
    b0602_parser_t parser={0};b0602_packet_t packet;
    CHECK(feed(&parser,golden,sizeof(golden),1000,&packet)==B0602_SCAN_PACKET);
    CHECK(packet.count==2 && packet.start_cdeg==2250 && packet.offset_cdeg==-100 && packet.speed_raw==124);
    CHECK(packet.points[0].distance_mm==1000 && packet.points[1].distance_mm==2000);
    CHECK(packet.points[0].angle_cdeg==2150 && packet.points[1].angle_cdeg==3275 && packet.points[0].quality==200);
    // Factory offset must wrap in both directions without disturbing raw sector numbering.
    uint8_t shifted[sizeof(golden)];memcpy(shifted,golden,sizeof(golden));
    shifted[11]=0;shifted[12]=0;crc_fill(shifted,sizeof(shifted));
    CHECK(feed(&parser,shifted,sizeof(shifted),1010,&packet)==B0602_SCAN_PACKET);
    CHECK(packet.start_cdeg==0 && packet.points[0].angle_cdeg==35900 && packet.points[1].angle_cdeg==1025);
    shifted[9]=0x0b;shifted[10]=0xb8;shifted[11]=0x83;shifted[12]=0xd6;crc_fill(shifted,sizeof(shifted));
    CHECK(feed(&parser,shifted,sizeof(shifted),1020,&packet)==B0602_SCAN_PACKET);
    CHECK(packet.start_cdeg==33750 && packet.points[0].angle_cdeg==750 && packet.points[1].angle_cdeg==1875);
    // Manufacturer V1.0 manual, physical-format reference frame (not a synthetic CRC).
    const uint8_t manual[]={0xAA,0x00,0x49,0x00,0x61,0xAD,0x00,0x41,0x83,0x00,0x00,0x08,0xCA,
        0x43,0x04,0xD5,0x4E,0x04,0xD0,0x48,0x04,0xD2,0x4D,0x04,0xD3,0x50,0x04,0xD6,
        0x3F,0x04,0xCF,0x5F,0x04,0xC4,0x5E,0x04,0xC9,0x70,0x04,0xCE,0x5C,0x04,0xD2,
        0x43,0x04,0xD1,0x44,0x04,0xD2,0x46,0x04,0xD0,0x4E,0x04,0xD3,0x51,0x04,0xD1,
        0x48,0x04,0xCF,0x44,0x04,0xD4,0x46,0x04,0xD2,0x52,0x04,0xD3,0x4B,0x04,0xCD,0x2A,0xB7};
    CHECK(b0602_crc(manual,sizeof(manual)-2)==0x2ab7);
    CHECK(feed(&parser,manual,sizeof(manual),1030,&packet)==B0602_SCAN_PACKET);
    CHECK(packet.count==20 && packet.speed_raw==131 && packet.points[0].distance_mm==309);
    CHECK(packet.points[19].distance_mm==307 && packet.points[19].angle_cdeg==4387);
    CHECK(lidar_sum16(captured_delta2a,sizeof(captured_delta2a)-2)==0x2ffc);
    CHECK(feed(&parser,captured_delta2a,sizeof(captured_delta2a),1040,&packet)==B0602_SCAN_PACKET);
    CHECK(packet.protocol_version==1 && packet.count==37 && packet.offset_cdeg==219 && packet.speed_raw==164);
    CHECK(packet.points[0].angle_cdeg==0 && packet.points[0].distance_mm==2150 && packet.points[0].quality==145);
    for(size_t i=0;i<sizeof(captured_delta2a);++i) {
        uint8_t bad[sizeof(captured_delta2a)];memcpy(bad,captured_delta2a,sizeof(bad));bad[i]^=1;
        memset(&parser,0,sizeof(parser));
        CHECK(feed(&parser,bad,sizeof(bad),1050,&packet)!=B0602_SCAN_PACKET);
    }
    uint8_t wrong_version[sizeof(captured_delta2a)];memcpy(wrong_version,captured_delta2a,sizeof(wrong_version));
    wrong_version[3]=2;crc_fill(wrong_version,sizeof(wrong_version));
    memset(&parser,0,sizeof(parser));CHECK(feed(&parser,wrong_version,sizeof(wrong_version),1060,&packet)!=B0602_SCAN_PACKET);
    for(size_t i=0;i<sizeof(golden);++i) {
        uint8_t corrupt[sizeof(golden)];memcpy(corrupt,golden,sizeof(golden));corrupt[i]^=1;
        memset(&parser,0,sizeof(parser));
        CHECK(feed(&parser,corrupt,sizeof(corrupt),1000,&packet)!=B0602_SCAN_PACKET);
        CHECK(feed(&parser,golden,sizeof(golden),1200,&packet)==B0602_SCAN_PACKET);
    }
    memset(&parser,0,sizeof(parser));
    CHECK(feed(&parser,golden,10,1000,&packet)==B0602_WAIT);
    CHECK(feed(&parser,golden+10,sizeof(golden)-10,1100,&packet)!=B0602_SCAN_PACKET);
    CHECK(parser.timeouts==1);
    CHECK(feed(&parser,golden,sizeof(golden),1300,&packet)==B0602_SCAN_PACKET);
    for(unsigned field=3;field<=7;++field) {
        uint8_t wrong[sizeof(golden)];memcpy(wrong,golden,sizeof(golden));wrong[field]^=2;crc_fill(wrong,sizeof(wrong));
        memset(&parser,0,sizeof(parser));CHECK(feed(&parser,wrong,sizeof(wrong),1000,&packet)!=B0602_SCAN_PACKET);
    }
    uint8_t health[]={0xaa,0,9,0,0x61,0xae,0,1,0,0,0};crc_fill(health,sizeof(health));
    memset(&parser,0,sizeof(parser));CHECK(feed(&parser,health,sizeof(health),1000,&packet)==B0602_SENSOR_FAULT);
    uint8_t malicious[]={0xaa,0xff,0xff,0xaa,0,0,0xaa,0,3};
    memset(&parser,0,sizeof(parser));feed(&parser,malicious,sizeof(malicious),1000,&packet);
    CHECK(feed(&parser,golden,sizeof(golden),1001,&packet)==B0602_SCAN_PACKET);
    struct { unsigned before; b0602_parser_t p; unsigned after; } guarded={0x12345678,{0},0x87654321};
    uint32_t rng=42;
    for(unsigned i=0;i<300000;++i){rng=rng*1664525u+1013904223u;b0602_feed(&guarded.p,(uint8_t)(rng>>24),i/16,&packet);}
    CHECK(guarded.before==0x12345678 && guarded.after==0x87654321 && guarded.p.used<=B0602_MAX_FRAME);
    CHECK(feed(&guarded.p,golden,sizeof(golden),30000,&packet)==B0602_SCAN_PACKET);
}
static b0602_packet_t tooth(unsigned index) {
    b0602_packet_t p={0};p.command=0xad;p.count=20;p.start_cdeg=(uint16_t)(2250*index);p.speed_raw=124;
    for(unsigned i=0;i<p.count;++i)p.points[i]=(lidar_point_t){(uint16_t)(p.start_cdeg+2250*i/p.count),1000,100};
    return p;
}
static void scan_tests(void) {
    b0602_scan_builder_t b={0};lidar_scan_t out={0};
    for(unsigned i=0;i<16;++i){b0602_packet_t p=tooth(i);CHECK(b0602_scan_add(&b,&p,1000+10*i,&out)==(i==15));}
    CHECK(out.complete && out.count==320 && out.valid_count==320 && out.nearest_mm==1000 && out.all_returns_valid);
    CHECK(out.started_ms==1000 && out.ended_ms==1150 && out.sequence==1);
    b0602_packet_t zero=tooth(0),two=tooth(2);
    CHECK(!b0602_scan_add(&b,&zero,2000,&out));CHECK(!b0602_scan_add(&b,&two,2020,&out));CHECK(!b.active);
    CHECK(out.sequence==1); // Incomplete cycle never overwrites the published scan.
    b0602_scan_add(&b,&zero,3000,&out);b0602_packet_t one=tooth(1);
    CHECK(!b0602_scan_add(&b,&one,3300,&out) && !b.active);
    for(unsigned i=0;i<16;++i){b0602_packet_t p=tooth(i);if(i==5)p.points[0].distance_mm=0;b0602_scan_add(&b,&p,4000+10*i,&out);}
    CHECK(out.complete && !out.all_returns_valid && out.valid_count==319 && out.count==320);
    for(unsigned i=0;i<16;++i){b0602_packet_t p=tooth(i);if(i==7)p.points[1].distance_mm=150;b0602_scan_add(&b,&p,5000+10*i,&out);}
    CHECK(out.nearest_mm==150);
    b0602_packet_t wrong=tooth(0);wrong.count=65;CHECK(!b0602_scan_add(&b,&wrong,6000,&out));
    b0602_scan_reset(&b);b0602_scan_add(&b,&zero,7000,&out);
    one.protocol_version=1;CHECK(!b0602_scan_add(&b,&one,7010,&out) && !b.active);
    for(unsigned i=0;i<16;++i){b0602_packet_t p=tooth(i);p.protocol_version=1;p.points[0].distance_mm=7000;p.points[0].quality=0;b0602_scan_add(&b,&p,8000+8*i,&out);}
    CHECK(out.complete && out.protocol_version==1 && out.valid_count==320 && out.all_returns_valid);
}
static motion_config_t config(void) {
    motion_config_t c={.enabled=true,.calibration_verified=true,.protocol_verified=true,.inhibit_verified=true,
        .neutral_us={1500,1500},.minimum_us={1400,1400},.maximum_us={1600,1600},.direction={1,-1},
        .slew_us_per_second=200,.stop_distance_mm=500,.slow_distance_mm=1000};return c;
}
static motion_inputs_t clear(void) {
    motion_inputs_t in={.link_live=true,.estop_released=true,.scan_fresh=true,.scan_complete=true,.all_returns_valid=true,.nearest_mm=2000};return in;
}
static void ready(motion_control_t *m,motion_inputs_t *in) {
    motion_config_t c=config();motion_init(m,&c);*in=clear();CHECK(motion_arm(m,1,1000,in));
    for(int t=1000;t<=1300;t+=10)motion_tick(m,t,in);
    CHECK(!m->output.driver_enable && m->output.pulse_us[0]==1500);
}
static void motion_tests(void) {
    motion_control_t m;motion_inputs_t in=clear();motion_init(&m,&RASTAG_MOTION_CONFIG);
    CHECK(!m.configured && !motion_arm(&m,1,1000,&in) && !m.output.driver_enable && !m.output.pulse_us[0]);
    CHECK(RASTAG_LIDAR_RX_GPIO==27 && RASTAG_SERVO_LEFT_GPIO==-1 && RASTAG_SERVO_RIGHT_GPIO==-1);
    motion_config_t c=config();CHECK(motion_config_valid(&c));c.calibration_verified=false;CHECK(!motion_config_valid(&c));
    c=config();c.neutral_us[0]=2400;CHECK(!motion_config_valid(&c));
    c=config();c.direction[1]=0;CHECK(!motion_config_valid(&c));
    ready(&m,&in);CHECK(motion_drive(&m,2,1000,1000,1500,1300,&in));motion_tick(&m,1310,&in);
    CHECK(m.output.driver_enable && m.output.pulse_us[0]==1502 && m.output.pulse_us[1]==1498);
    for(int t=1320;t<1500;t+=10)motion_tick(&m,t,&in);
    motion_tick(&m,1500,&in);CHECK(!m.armed && !m.output.driver_enable && m.reason==MOTION_EXPIRED && m.output.pulse_us[0]==1500);
    for(unsigned fault=0;fault<6;++fault) {
        ready(&m,&in);CHECK(motion_drive(&m,2,1000,-1000,1500,1300,&in));motion_tick(&m,1310,&in);
        switch(fault){case 0:in.link_live=false;break;case 1:in.estop_released=false;break;case 2:in.scan_fresh=false;break;
            case 3:in.scan_complete=false;break;case 4:in.all_returns_valid=false;break;case 5:in.nearest_mm=500;break;}
        motion_tick(&m,1320,&in);CHECK(!m.armed && !m.output.driver_enable && m.output.pulse_us[0]==1500);
        CHECK(!motion_arm(&m,3,1330,&in));
    }
    ready(&m,&in);CHECK(motion_drive(&m,2,1000,1000,1500,1300,&in));
    CHECK(!motion_drive(&m,2,1000,1000,1510,1310,&in) && !m.armed); // replay cannot extend a command
    ready(&m,&in);CHECK(!motion_drive(&m,2,1,1,1551,1300,&in));
    ready(&m,&in);CHECK(!motion_drive(&m,2,1,1,1300,1300,&in));
    ready(&m,&in);CHECK(!motion_drive(&m,2,1001,0,1500,1300,&in));
    ready(&m,&in);CHECK(motion_drive(&m,2,1,1,1500,1300,&in));motion_tick(&m,1401,&in);CHECK(m.reason==MOTION_LOOP_STALLED && !m.armed);
    ready(&m,&in);motion_new_session(&m);CHECK(!m.armed && !m.output.driver_enable && m.sequence==0);
    ready(&m,&in);in.nearest_mm=750;CHECK(motion_drive(&m,2,1000,-1000,1550,1300,&in));
    for(int t=1310;t<=1540;t+=10)motion_tick(&m,t,&in);
    CHECK(m.output.pulse_us[0]<=1550 && m.output.pulse_us[1]<=1550);
    motion_stop(&m,MOTION_STOP_REQUEST);CHECK(!m.armed && !m.output.driver_enable && m.output.pulse_us[0]==1500);
}
static void link_tests(void) {
    link_session_t s={0};const char *token="0123456789abcdef0123456789abcdef";
    CHECK(!link_matches(&s,token));link_new_session(&s,token,1000);CHECK(link_live(&s,7999) && !link_live(&s,8000));
    CHECK(!link_heartbeat(&s,token,1,8000));CHECK(link_matches(&s,token)); // authenticated stop may outlive heartbeat lease
    link_new_session(&s,token,1000);CHECK(link_heartbeat(&s,token,1,1001));CHECK(!link_heartbeat(&s,token,1,1002));
    CHECK(!link_matches(&s,"1123456789abcdef0123456789abcdef"));
}
#include "lidar_wire.h"
static void wire_tests(void) {
    CHECK(lidar_wire_crc32((const uint8_t *)"123456789",9)==0xcbf43926u);
    lidar_scan_t scan={.sequence=0xffffffffu,.started_ms=1000,.ended_ms=1120,.complete=true,.count=4,.protocol_version=1};
    scan.points[0]=(lidar_point_t){0,250,200};scan.points[1]=(lidar_point_t){9000,8000,0};
    scan.points[2]=(lidar_point_t){18000,0,0};scan.points[3]=(lidar_point_t){35999,145,1};
    const char *boot="00112233445566778899aabbccddeeff";
    uint8_t out[LIDAR_WIRE_MAX+1];memset(out,0x5a,sizeof(out));
    size_t n=lidar_wire_encode(&scan,boot,1140,out,sizeof(out)-1);
    CHECK(n==92 && out[n]==0x5a && !memcmp(out,"RLS2",4));
    CHECK(out[4]==2 && out[6]==64 && out[8]==92 && out[12]==255 && out[16]==4 && out[18]==6 && out[20]==1);
    CHECK(out[48]==0 && out[63]==255 && out[69]==1 && out[75]==1 && out[81]==0 && out[87]==0);
    FILE *golden_file=fopen("artifacts/rastag-lidar-wire-golden.bin","wb");CHECK(golden_file!=NULL);
    CHECK(fwrite(out,1,n,golden_file)==n);CHECK(fclose(golden_file)==0);
    CHECK(!lidar_wire_encode(&scan,boot,1140,out,91));
    CHECK(!lidar_wire_encode(&scan,"invalid",1140,out,sizeof(out)));
    CHECK(!lidar_wire_encode(&scan,"00112233445566778899aabbccddeefz",1140,out,sizeof(out)));
    CHECK(!lidar_wire_encode(&scan,boot,1119,out,sizeof(out)));
    CHECK(!lidar_wire_encode(&scan,boot,1420,out,sizeof(out)));
    scan.complete=false;CHECK(!lidar_wire_encode(&scan,boot,1140,out,sizeof(out)));scan.complete=true;
    scan.points[3].angle_cdeg=36000;CHECK(!lidar_wire_encode(&scan,boot,1140,out,sizeof(out)));scan.points[3].angle_cdeg=35999;
    scan.count=B0602_MAX_SCAN+1;CHECK(!lidar_wire_encode(&scan,boot,1140,out,sizeof(out)));
    scan.count=B0602_MAX_SCAN;CHECK(lidar_wire_encode(&scan,boot,1140,out,LIDAR_WIRE_MAX)==LIDAR_WIRE_MAX);
    CHECK(out[LIDAR_WIRE_MAX]==0x5a);
}
int main(void) {
    parser_tests();scan_tests();motion_tests();link_tests();wire_tests();
    printf("PASS: %u assertions; 300000 fuzz bytes; outputs disabled by shipping configuration\n",checks);return 0;
}
