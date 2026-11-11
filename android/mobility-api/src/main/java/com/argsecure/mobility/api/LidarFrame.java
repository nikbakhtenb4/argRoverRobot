package com.argsecure.mobility.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.zip.CRC32;

/** Bounded RLS2 scan. All distances/angles are already decoded by the ESP. */
public final class LidarFrame {
    public static final int MAX_BYTES = 64 + 6*1024 + 4;
    public static final long LIVE_MS = 1500;
    public final long sequence, startedMs, endedMs, sentMs;
    public final String boot;
    public final int count, validCount, nearestMm;
    private final byte[] bytes;
    private LidarFrame(byte[] raw) {
        bytes=raw; ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        sequence=u32(b,12); count=u16(b,16); startedMs=b.getLong(24); endedMs=b.getLong(32); sentMs=b.getLong(40);
        StringBuilder id=new StringBuilder(32);for(int i=48;i<64;i++)id.append(String.format(Locale.US,"%02x",raw[i]&255));boot=id.toString();
        int valid=0,nearest=Integer.MAX_VALUE;
        for(int i=0;i<count;i++)if(valid(i)){valid++;nearest=Math.min(nearest,distanceMm(i));}
        validCount=valid;nearestMm=valid==0?0:nearest;
    }
    public static LidarFrame parse(byte[] raw) {
        if(raw==null||raw.length<74||raw.length>MAX_BYTES)throw new IllegalArgumentException("Frame size");
        byte[] copy=raw.clone();ByteBuffer b=ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
        int count=u16(b,16);long flags=u32(b,20);
        if(b.getInt(0)!=0x32534c52||u16(b,4)!=2||u16(b,6)!=64||u32(b,8)!=copy.length
                ||count<1||count>1024||u16(b,18)!=6||copy.length!=68+6*count||(flags&~3L)!=0||(flags&1)==0)
            throw new IllegalArgumentException("Frame header");
        CRC32 crc=new CRC32();crc.update(copy,0,copy.length-4);
        if(crc.getValue()!=u32(b,copy.length-4))throw new IllegalArgumentException("Frame checksum");
        long start=b.getLong(24),end=b.getLong(32),sent=b.getLong(40);
        if(start<0||end<start||end-start>300||sent<end||sent-end>=300)throw new IllegalArgumentException("Frame time");
        boolean all=true;
        for(int i=0;i<count;i++){
            int p=64+i*6,valid=copy[p+5]&255,distance=u16(b,p+2);
            if(u16(b,p)>=36000||valid>1||(valid==1&&(distance<130||distance>8000)))throw new IllegalArgumentException("Frame point");
            all&=valid==1;
        }
        if(all!=((flags&2)!=0))throw new IllegalArgumentException("Frame validity");
        return new LidarFrame(copy);
    }
    public byte[] bytes(){return bytes.clone();}
    public int angleCdeg(int i){return word(offset(i));}
    public int distanceMm(int i){return word(offset(i)+2);}
    public int quality(int i){return bytes[offset(i)+4]&255;}
    public boolean valid(int i){return bytes[offset(i)+5]==1;}
    private int offset(int i){if(i<0||i>=count)throw new IndexOutOfBoundsException();return 64+i*6;}
    private int word(int p){return (bytes[p]&255)|((bytes[p+1]&255)<<8);}
    private static int u16(ByteBuffer b,int at){return b.getShort(at)&65535;}
    private static long u32(ByteBuffer b,int at){return b.getInt(at)&0xffffffffL;}
    /** Unsigned wraparound; within a boot, duplicates/out-of-order scans cannot refresh freshness. */
    public boolean isNewerThan(LidarFrame previous){
        if(previous==null||!boot.equals(previous.boot))return true;
        long delta=(sequence-previous.sequence)&0xffffffffL;
        return delta>0&&delta<0x80000000L&&endedMs>previous.endedMs&&sentMs>=previous.sentMs;
    }
    /** Conservative age upper bound: source age + whole request RTT + time since receipt. */
    public long ageMs(long receivedElapsed,long roundTripMs,long now){
        if(receivedElapsed<0||now<receivedElapsed||roundTripMs<0||roundTripMs>LIVE_MS)return Long.MAX_VALUE;
        long age=now-receivedElapsed;
        if(age>=LIVE_MS)return Long.MAX_VALUE;
        return sentMs-endedMs+roundTripMs+age;
    }
}
