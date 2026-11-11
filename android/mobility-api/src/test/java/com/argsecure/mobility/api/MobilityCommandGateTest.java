package com.argsecure.mobility.api;

import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class MobilityCommandGateTest {
    private String request(String op) {
        return "{\"version\":1,\"request_id\":\"0123456789abcdef0123456789abcdef\",\"issued_elapsed_ms\":10000,\"ttl_ms\":1000,\"operation\":\""+op+"\"}";
    }
    private String code(MobilityCommandGate gate,String json,long now) {
        com.google.gson.JsonObject result=JsonParser.parseString(gate.evaluate(json,now)).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        assertFalse(result.get("motion_enabled").getAsBoolean());
        assertFalse(result.get("stop_confirmed").getAsBoolean());
        return result.get("code").getAsString();
    }
    @Test public void movementCannotBeEnabledByBroadcast() {
        for (String op : new String[]{"move","arm","stop"}) assertEquals("motion_unavailable",code(new MobilityCommandGate(),request(op),10001));
    }
    @Test public void missingLidarAndMappingAreExplicit() {
        assertEquals("lidar_unavailable",code(new MobilityCommandGate(),request("lidar_start"),10001));
        assertEquals("mapping_unavailable",code(new MobilityCommandGate(),request("mapping_start"),10001));
    }
    @Test public void delayedAndFutureRequestsAreRejected() {
        assertEquals("expired_request",code(new MobilityCommandGate(),request("move"),11000));
        assertEquals("expired_request",code(new MobilityCommandGate(),request("move"),9999));
    }
    @Test public void retryDoesNotExecuteTwice() {
        MobilityCommandGate gate=new MobilityCommandGate();
        assertEquals("motion_unavailable",code(gate,request("stop"),10001));
        assertEquals("duplicate_request",code(gate,request("stop"),10002));
    }
    @Test public void malformedOversizedAndInjectedFieldsAreRejected() {
        for(String json:new String[]{"{}", "null",request("move").replace("1000,","3001,"),
                request("move").replace("10000,","10000.5,"), request("move").replace("}",",\"speed\":1}"),
                request("move").replace("\"version\":1","\"version\":\"1\""),new String(new char[2049])})
            assertEquals("invalid_request",code(new MobilityCommandGate(),json,10001));
    }
    @Test public void unknownOperationNeverFallsThroughToAnActuator() {
        assertEquals("unknown_operation",code(new MobilityCommandGate(),request("shell"),10001));
    }
}
