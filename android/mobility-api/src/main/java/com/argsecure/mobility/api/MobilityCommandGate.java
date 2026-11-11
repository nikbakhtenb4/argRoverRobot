package com.argsecure.mobility.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;

/** Rejects unsafe/unsupported requests explicitly. It has no actuator or network access. */
public final class MobilityCommandGate {
    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    public synchronized String evaluate(String raw, long now) {
        String id = "", code = "invalid_request";
        try {
            if (raw == null || raw.length() > 2048) throw new IllegalArgumentException();
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            String candidate = MobilitySnapshot.text(o,"request_id");
            if (!candidate.matches("[0-9a-f]{32}")) throw new IllegalArgumentException();
            id = candidate;
            long issued = MobilitySnapshot.number(o,"issued_elapsed_ms"), ttl = MobilitySnapshot.number(o,"ttl_ms");
            if (o.size() != 5 || MobilitySnapshot.number(o,"version") != MobilityContract.VERSION
                    || issued <= 0 || ttl <= 0 || ttl > 3000) throw new IllegalArgumentException();
            String operation = MobilitySnapshot.text(o,"operation");
            if (now < issued || now - issued >= ttl) code = "expired_request";
            else if (seen.containsKey(id)) code = "duplicate_request";
            else {
                seen.put(id, Boolean.TRUE);
                if (seen.size() > 128) seen.remove(seen.keySet().iterator().next());
                switch (operation) {
                    case "move": case "arm": case "stop": code = "motion_unavailable"; break;
                    case "mapping_start": case "mapping_stop": code = "mapping_unavailable"; break;
                    case "lidar_start": case "lidar_stop": code = "lidar_unavailable"; break;
                    default: code = "unknown_operation"; break;
                }
            }
        } catch (RuntimeException ignored) { }
        return result(id, code);
    }
    private static String result(String id, String code) {
        JsonObject o = new JsonObject();
        o.addProperty("version",MobilityContract.VERSION); o.addProperty("request_id",id);
        o.addProperty("success",false); o.addProperty("code",code);
        o.addProperty("motion_enabled",false); o.addProperty("stop_confirmed",false);
        return o.toString();
    }
}
