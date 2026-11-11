package com.argsecure.mobility.api;

/** Immutable reception timing travels with the frame; rebroadcasting never renews it. */
public final class LidarReading {
    public final LidarFrame frame;
    public final long receivedElapsed, roundTripMs;
    public LidarReading(LidarFrame frame,long received,long roundTrip){
        if(frame==null||received<0||roundTrip<0||roundTrip>LidarFrame.LIVE_MS)throw new IllegalArgumentException();
        this.frame=frame;receivedElapsed=received;roundTripMs=roundTrip;
    }
    public long ageMs(long now){return frame.ageMs(receivedElapsed,roundTripMs,now);}
    public boolean live(long now){return ageMs(now)<LidarFrame.LIVE_MS;}
    public static LidarReading receive(byte[] bytes,String expectedBoot,long requested,long received,LidarFrame previous){
        LidarFrame frame=LidarFrame.parse(bytes);
        if(!frame.boot.equals(expectedBoot)||requested<0||received<requested)throw new IllegalArgumentException("Frame origin/time");
        LidarReading reading=new LidarReading(frame,received,received-requested);
        if(!reading.live(received))throw new IllegalArgumentException("Expired frame");
        return frame.isNewerThan(previous)?reading:null;
    }
}
