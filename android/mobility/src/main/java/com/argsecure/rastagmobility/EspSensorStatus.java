package com.argsecure.rastagmobility;

import com.google.gson.*;

/** Read-only v2 observation. No session tokens, addresses or actuator values leave this object. */
final class EspSensorStatus {
    static final String ACTION="com.argsecure.rastagmobility.MAPPING_HEALTH";
    static final EspSensorStatus UNKNOWN=new EspSensorStatus("unavailable",0,0);
    final String state;
    final int points;
    final long received;
    EspSensorStatus(String state,int points,long received){this.state=state;this.points=points;this.received=received;}
    String state(long now){return received<=0||now<received||now-received>=6000?"unavailable":state;}
    static EspSensorStatus parse(String raw,String expectedBoot,long now){
        if(raw==null||raw.length()>2048)throw new IllegalArgumentException();
        JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
        if(MapDefinition.integer(o.get("protocol"))!=2||!MapDefinition.text(o.get("boot")).equals(expectedBoot))throw new IllegalArgumentException();
        JsonObject l=o.getAsJsonObject("lidar");
        boolean configured=bool(l,"configured"),fresh=bool(l,"fresh"),verified=bool(l,"hardware_verified"),complete=bool(l,"complete");
        int points=MapDefinition.integer(l.get("points"));int age=MapDefinition.integer(l.get("scan_age_ms"));
        if(points<0||points>1024||age< -1)throw new IllegalArgumentException();
        String state=!configured?"not_configured":!verified?"unverified":!fresh||!complete||points==0||age<0||age>=300?"no_data":"ready";
        return new EspSensorStatus(state,points,now);
    }
    private static boolean bool(JsonObject o,String key){JsonPrimitive p=o.getAsJsonPrimitive(key);if(!p.isBoolean())throw new IllegalArgumentException();return p.getAsBoolean();}
    String json(long now){JsonObject o=new JsonObject();o.addProperty("version",1);o.addProperty("published_elapsed_ms",now);
        o.addProperty("lidar_state",state(now));o.addProperty("points",points);o.addProperty("capture_available",false);return o.toString();}
}
