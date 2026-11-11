package com.argsecure.mobility.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Only aggregate capability/health data; never credentials, addresses, scans or motor setpoints. */
public final class MobilitySnapshot {
    public final String instance;
    public final long serial, publishedElapsedMs, ackElapsedMs;
    public final LinkHealth.State link;
    public final boolean motionEnabled;
    public final String lidarState;

    public MobilitySnapshot(String instance, long serial, long published, long ack, LinkHealth.State link) {
        this(instance,serial,published,ack,link,false,"not_configured");
    }
    public MobilitySnapshot(String instance,long serial,long published,long ack,LinkHealth.State link,boolean motionEnabled,String lidarState) {
        this.instance = instance; this.serial = serial; publishedElapsedMs = published;
        ackElapsedMs = ack; this.link = link;
        this.motionEnabled=motionEnabled;this.lidarState=lidarState;
    }
    public LinkHealth.State state(long now) {
        long age = now - publishedElapsedMs;
        if (age < 0 || age >= MobilityContract.STATUS_MAX_AGE_MS) return LinkHealth.State.SERVICE_UNAVAILABLE;
        if (link == LinkHealth.State.CONNECTED && (ackElapsedMs <= 0 || now < ackElapsedMs
                || now - ackElapsedMs >= MobilityContract.LEASE_MS)) return LinkHealth.State.DISCONNECTED;
        return link;
    }
    public boolean isNewerThan(MobilitySnapshot previous) {
        return previous == null || (publishedElapsedMs >= previous.publishedElapsedMs
                && (instance.equals(previous.instance) ? serial > previous.serial : publishedElapsedMs > previous.publishedElapsedMs));
    }
    public String json() {
        JsonObject o = new JsonObject();
        o.addProperty("version", MobilityContract.VERSION); o.addProperty("instance", instance);
        o.addProperty("serial", serial); o.addProperty("published_elapsed_ms", publishedElapsedMs);
        o.addProperty("ack_elapsed_ms", ackElapsedMs); o.addProperty("link", link.name());
        o.addProperty("motion_enabled", motionEnabled); o.addProperty("lidar_state", lidarState);
        o.addProperty("mapping_state", "awaiting_lidar_and_odometry");
        return o.toString();
    }
    public static MobilitySnapshot parse(String raw, long now) {
        if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("Status size");
        JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
        if (number(o,"version") != MobilityContract.VERSION || !text(o,"instance").matches("[0-9a-f]{32}")
                || !o.getAsJsonPrimitive("motion_enabled").isBoolean()
                || !text(o,"lidar_state").matches("not_configured|live|unavailable")
                || !"awaiting_lidar_and_odometry".equals(text(o,"mapping_state"))) throw new IllegalArgumentException("Status contract");
        MobilitySnapshot s = new MobilitySnapshot(text(o,"instance"),number(o,"serial"),number(o,"published_elapsed_ms"),
                number(o,"ack_elapsed_ms"),LinkHealth.State.valueOf(text(o,"link")),o.get("motion_enabled").getAsBoolean(),text(o,"lidar_state"));
        if (s.serial < 0 || s.publishedElapsedMs <= 0 || s.ackElapsedMs < 0 || s.ackElapsedMs > s.publishedElapsedMs
                || s.state(now) == LinkHealth.State.SERVICE_UNAVAILABLE) throw new IllegalArgumentException("Status age");
        return s;
    }
    static long number(JsonObject o, String field) {
        if (!o.getAsJsonPrimitive(field).isNumber()) throw new IllegalArgumentException("Number required");
        return o.get(field).getAsBigDecimal().longValueExact();
    }
    static String text(JsonObject o, String field) {
        if (!o.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException("Text required");
        return o.get(field).getAsString();
    }
}
