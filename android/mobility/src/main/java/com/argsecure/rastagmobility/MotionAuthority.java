package com.argsecure.rastagmobility;
import com.argsecure.mobility.api.MotionContract;
import com.google.gson.*;
import java.util.LinkedHashMap;

/** Coalesced deadman, finite external commands, deduplication and human priority. */
final class MotionAuthority {
    static final class Demand {
        final long run;final String source,operation;final int a,b;
        long until;
        Demand(long run,String source,String op,int a,int b,long until){this.run=run;this.source=source;operation=op;this.a=a;this.b=b;this.until=until;}
    }
    private long generation,remoteUntil;
    private Demand active;
    private boolean stopPending;
    private final LinkedHashMap<String,Boolean> seen=new LinkedHashMap<>();
    synchronized void allowRemote(boolean allow,long now){remoteUntil=allow?now+60000:0;if(!allow&&active!=null&&!active.source.equals("manual"))stop();}
    synchronized boolean remoteAllowed(long now){return now<remoteUntil;}
    synchronized long beginManual(String op,int a,int b,long now){
        if(!op.equals("drive")&&!op.equals("jog"))throw new IllegalArgumentException();
        if(op.equals("drive")&&(a<1||a>4||b<1||b>100))throw new IllegalArgumentException();
        if(op.equals("jog")&&(a<8000||a>12000||b<8000||b>12000))throw new IllegalArgumentException();
        stop();active=new Demand(++generation,"manual",op,a,b,now+200);return generation;
    }
    synchronized void renew(long run,long now){if(active!=null&&active.run==run&&active.source.equals("manual")&&now<active.until)active.until=now+200;}
    synchronized void release(long run){if(active!=null&&active.run==run)stop();}
    synchronized void stop(){active=null;stopPending=true;}
    synchronized void failed(long run){if(active!=null&&active.run==run)stop();}
    synchronized boolean takeStop(){boolean value=stopPending;stopPending=false;return value;}
    synchronized Demand current(long now){
        if(active!=null&&(now>=active.until||(!active.source.equals("manual")&&!remoteAllowed(now))))stop();
        return active;
    }
    synchronized String external(String raw,long now){
        try{
            if(raw==null||raw.length()>2048)throw new IllegalArgumentException();
            JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
            if(o.size()!=9||number(o,"version")!=1)throw new IllegalArgumentException();
            String id=text(o,"request_id"),source=text(o,"source"),op=text(o,"operation"),direction=text(o,"direction");
            long issued=number(o,"issued_elapsed_ms"),ttl=number(o,"ttl_ms"),duration=number(o,"duration_ms"),intensity=number(o,"intensity");
            if(!id.matches("[a-f0-9]{32}")||!source.matches("human|ai")||ttl<1||ttl>3000||issued<1||now<issued||now-issued>=ttl)return "expired_request";
            if(seen.containsKey(id))return "duplicate_request";
            seen.put(id,true);if(seen.size()>128)seen.remove(seen.keySet().iterator().next());
            int dir=MotionContract.direction(direction);
            if(op.equals("stop")&&dir==0&&duration==0&&intensity==0){stop();return "queued";}
            if(!op.equals("drive")||dir==0||intensity<1||intensity>100||duration<1||duration>3000)throw new IllegalArgumentException();
            if(!remoteAllowed(now))return "remote_control_locked";
            Demand previous=current(now);
            if(previous!=null&&(previous.source.equals("manual")||(previous.source.equals("human")&&source.equals("ai"))))return "manual_priority";
            stop();active=new Demand(++generation,source,"drive",dir,(int)intensity,Math.min(issued+ttl,now+duration));return "queued";
        }catch(RuntimeException e){return "invalid_request";}
    }
    private static long number(JsonObject o,String k){if(!o.getAsJsonPrimitive(k).isNumber())throw new IllegalArgumentException();return o.get(k).getAsBigDecimal().longValueExact();}
    private static String text(JsonObject o,String k){if(!o.getAsJsonPrimitive(k).isString())throw new IllegalArgumentException();return o.get(k).getAsString();}
}
