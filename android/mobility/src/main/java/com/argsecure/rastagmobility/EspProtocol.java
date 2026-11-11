package com.argsecure.rastagmobility;

import com.argsecure.mobility.api.LinkHealth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;

/** Authenticated session and heartbeat protocol; actuator requests use the v3 contract. */
final class EspProtocol {
    static final long LEASE_MS = 7000;
    static final class Ack {
        final String boot, session;
        final long sequence;
        Ack(String b, String s, long n) { boot = b; session = s; sequence = n; }
    }
    static Ack parse(String raw, String device, String boot, String session, long seq) throws IOException {
        try {
            if (raw.length() > 2048) throw new IllegalArgumentException();
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            if (number(o, "protocol") != 1 || !text(o,"device").equals(device)
                    || number(o,"lease_ms") != LEASE_MS || number(o,"seq") != seq
                    || !o.get("live").isJsonPrimitive() || !o.getAsJsonPrimitive("live").isBoolean() || !o.get("live").getAsBoolean()
                    || !o.get("motion_enabled").isJsonPrimitive() || !o.getAsJsonPrimitive("motion_enabled").isBoolean()) throw new IllegalArgumentException();
            String b = text(o,"boot"), s = text(o,"session");
            if (!b.matches("[0-9a-f]{32}") || !s.matches("[0-9a-f]{32}")
                    || (boot != null && !boot.equals(b)) || (session != null && !session.equals(s))) throw new IllegalArgumentException();
            return new Ack(b,s,seq);
        } catch (RuntimeException error) { throw new IOException("Invalid controller acknowledgement"); }
    }
    private static String text(JsonObject o, String key) {
        if (!o.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException();
        return o.get(key).getAsString();
    }
    private static long number(JsonObject o, String key) {
        if (!o.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException();
        return o.get(key).getAsBigDecimal().longValueExact();
    }
    static String heartbeat(String session, long sequence) {
        if (!session.matches("[0-9a-f]{32}") || sequence < 1 || sequence > 0xffffffffL) throw new IllegalArgumentException();
        return "{\"session\":\"" + session + "\",\"seq\":" + sequence + "}";
    }
}
