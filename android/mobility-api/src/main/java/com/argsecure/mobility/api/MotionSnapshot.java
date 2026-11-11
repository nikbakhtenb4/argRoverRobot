package com.argsecure.mobility.api;
import com.google.gson.*;

/** Public motion decisions, not a claim that physical velocity is measured. */
public final class MotionSnapshot {
    public final String requested,decision,reason,source;
    public final long published;
    public final boolean configured,driving;
    public MotionSnapshot(String requested,String decision,String reason,String source,long now,boolean configured,boolean driving){
        MotionContract.direction(requested);MotionContract.direction(decision);
        this.requested=requested;this.decision=decision;this.reason=reason;this.source=source;
        published=now;this.configured=configured;this.driving=driving;
    }
    public boolean fresh(long now){return now>=published&&now-published<4000;}
    public String json(){JsonObject o=new JsonObject();o.addProperty("version",1);o.addProperty("published_elapsed_ms",published);
        o.addProperty("requested",requested);o.addProperty("decision",decision);o.addProperty("reason",reason);o.addProperty("source",source);
        o.addProperty("configured",configured);o.addProperty("driving",driving);o.addProperty("physical_motion_verified",false);return o.toString();}
    public static MotionSnapshot parse(String raw,long now){
        if(raw==null||raw.length()>2048)throw new IllegalArgumentException();
        JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
        if(MobilitySnapshot.number(o,"version")!=1||o.get("physical_motion_verified").getAsBoolean())throw new IllegalArgumentException();
        for(String key:new String[]{"configured","driving","physical_motion_verified"})if(!o.getAsJsonPrimitive(key).isBoolean())throw new IllegalArgumentException();
        MotionSnapshot s=new MotionSnapshot(MobilitySnapshot.text(o,"requested"),MobilitySnapshot.text(o,"decision"),
            MobilitySnapshot.text(o,"reason"),MobilitySnapshot.text(o,"source"),MobilitySnapshot.number(o,"published_elapsed_ms"),
            o.get("configured").getAsBoolean(),o.get("driving").getAsBoolean());
        if(!s.fresh(now)||!s.source.matches("manual|human|ai|none")||!s.reason.matches("[a-z_]{1,48}"))throw new IllegalArgumentException();return s;
    }
}
