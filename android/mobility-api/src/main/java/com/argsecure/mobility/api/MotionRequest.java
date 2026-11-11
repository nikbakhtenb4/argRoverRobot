package com.argsecure.mobility.api;
import com.google.gson.JsonObject;

/** Shared request builder for human commands and internal AI planners. No automatic retries. */
public final class MotionRequest {
    private MotionRequest(){}
    public static String json(String id,String source,String direction,int intensity,int durationMs,long now){
        int d=MotionContract.direction(direction);
        if(id==null||!id.matches("[0-9a-f]{32}")||!source.matches("human|ai")||now<=0
                ||(d==0?(intensity!=0||durationMs!=0):(intensity<1||intensity>100||durationMs<1||durationMs>3000)))throw new IllegalArgumentException();
        JsonObject o=new JsonObject();o.addProperty("version",1);o.addProperty("request_id",id);o.addProperty("source",source);
        o.addProperty("issued_elapsed_ms",now);o.addProperty("ttl_ms",3000);o.addProperty("operation",d==0?"stop":"drive");
        o.addProperty("direction",direction);o.addProperty("intensity",intensity);o.addProperty("duration_ms",durationMs);return o.toString();
    }
}
