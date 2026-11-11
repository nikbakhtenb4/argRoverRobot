package com.argsecure.mobility.api;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.util.Arrays;
import java.util.zip.CRC32;

public class LidarFrameTest {
    private byte[] golden() throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/lidar-rls2-golden.bin")){
            assertNotNull("Actual C encoder fixture",in);ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[256];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();
        }
    }
    private byte[] crc(byte[] raw){CRC32 crc=new CRC32();crc.update(raw,0,raw.length-4);b(raw).putInt(raw.length-4,(int)crc.getValue());return raw;}
    private ByteBuffer b(byte[] raw){return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);}
    private void reject(byte[] raw){try{LidarFrame.parse(raw);fail("Malformed frame accepted");}catch(IllegalArgumentException expected){}}
    @Test public void readsActualFirmwareEncoderBytes() throws Exception {
        LidarFrame f=LidarFrame.parse(golden());assertEquals(0xffffffffL,f.sequence);assertEquals(4,f.count);
        assertEquals(2,f.validCount);assertEquals(250,f.nearestMm);assertEquals(35999,f.angleCdeg(3));
        assertEquals(8000,f.distanceMm(1));assertTrue(f.valid(1));assertFalse(f.valid(3));assertEquals(0,f.quality(1));
        assertEquals("00112233445566778899aabbccddeeff",f.boot);assertEquals(1000,f.startedMs);assertEquals(1120,f.endedMs);assertEquals(1140,f.sentMs);
    }
    @Test public void rejectsEveryTruncationAndBitCorruption() throws Exception {
        byte[] raw=golden();for(int i=0;i<raw.length;i++){reject(Arrays.copyOf(raw,i));byte[] bad=raw.clone();bad[i]^=1;reject(bad);}
        reject(Arrays.copyOf(raw,raw.length+1));reject(new byte[LidarFrame.MAX_BYTES+1]);reject(null);
    }
    @Test public void rejectsInvalidHeadersEvenWithValidChecksum() throws Exception {
        for(int offset:new int[]{4,6,8,16,18,20}){byte[] raw=golden();b(raw).putShort(offset,(short)0);reject(crc(raw));}
        byte[] raw=golden();b(raw).putInt(20,5);reject(crc(raw));
        raw=golden();b(raw).putInt(20,3);reject(crc(raw));
    }
    @Test public void rejectsInvalidTimesPointsAndFlags() throws Exception {
        byte[] raw=golden();b(raw).putLong(24,-1);reject(crc(raw));
        raw=golden();b(raw).putLong(24,1121);reject(crc(raw));
        raw=golden();b(raw).putLong(24,819);reject(crc(raw));
        raw=golden();b(raw).putLong(40,1119);reject(crc(raw));
        raw=golden();b(raw).putLong(40,1420);reject(crc(raw));
        raw=golden();b(raw).putShort(64,(short)36000);reject(crc(raw));
        raw=golden();raw[69]=2;reject(crc(raw));
        raw=golden();b(raw).putShort(66,(short)129);reject(crc(raw));
        raw=golden();b(raw).putShort(66,(short)8001);reject(crc(raw));
    }
    @Test public void freshnessIncludesNetworkAndSourceAgeAndNeverRenewsDuplicates() throws Exception {
        LidarFrame f=LidarFrame.parse(golden());LidarReading r=LidarReading.receive(golden(),f.boot,5000,5100,null);
        assertEquals(120,r.ageMs(5100));assertTrue(r.live(6479));assertFalse(r.live(6480));assertFalse(r.live(5099));
        assertNull(LidarReading.receive(golden(),f.boot,6000,6100,f));
        try{LidarReading.receive(golden(),"wrong-boot",5000,5100,null);fail();}catch(IllegalArgumentException expected){}
        try{LidarReading.receive(golden(),f.boot,5000,6480,null);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void handlesSequenceWrapReorderingAndReboot() throws Exception {
        LidarFrame before=LidarFrame.parse(golden());byte[] raw=golden();b(raw).putInt(12,0);b(raw).putLong(32,1130);b(raw).putLong(40,1150);
        LidarFrame after=LidarFrame.parse(crc(raw));assertTrue(after.isNewerThan(before));assertFalse(before.isNewerThan(after));
        b(raw).putInt(12,1);b(raw).putLong(32,1119);assertFalse(LidarFrame.parse(crc(raw)).isNewerThan(after));
        raw[48]^=1;assertTrue(LidarFrame.parse(crc(raw)).isNewerThan(after));
    }
    @Test public void decodedFrameCannotBeMutatedBySenderOrConsumer() throws Exception {
        byte[] raw=golden();LidarFrame f=LidarFrame.parse(raw);raw[66]=0;byte[] copy=f.bytes();copy[66]=0;assertEquals(250,f.distanceMm(0));
    }
}
