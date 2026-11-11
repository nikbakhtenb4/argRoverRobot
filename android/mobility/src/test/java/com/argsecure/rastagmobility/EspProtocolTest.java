package com.argsecure.rastagmobility;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

public class EspProtocolTest {
    private final String device = "rastag-esp-083af2b76f3c";
    private final String token = "0123456789abcdef0123456789abcdef";
    private String ack() { return "{\"protocol\":1,\"device\":\""+device+"\",\"boot\":\""+token+"\",\"session\":\""+token
            +"\",\"seq\":2,\"lease_ms\":7000,\"live\":true,\"motion_enabled\":false}"; }
    @Test public void acceptsAuthenticatedCurrentAck() throws Exception {
        assertEquals(2,EspProtocol.parse(ack(),device,token,token,2).sequence);
    }
    private void reject(String raw, String d, String b, String s, long seq) {
        try { EspProtocol.parse(raw,d,b,s,seq); fail("Invalid acknowledgement accepted"); }
        catch (IOException expected) { }
    }
    @Test public void rejectsWrongDeviceAndReplay() {
        reject(ack(),"other",token,token,2); reject(ack(),device,token,token,3);
    }
    @Test public void rejectsRebootAndReplacedSession() {
        reject(ack(),device,"a"+token.substring(1),token,2);
        reject(ack(),device,token,"b"+token.substring(1),2);
    }
    @Test public void rejectsExpiredAndAcceptsAuthenticatedActuationState() throws Exception {
        reject(ack().replace("\"live\":true","\"live\":false"),device,token,token,2);
        assertEquals(2,EspProtocol.parse(ack().replace("\"motion_enabled\":false","\"motion_enabled\":true"),device,token,token,2).sequence);
        reject(ack().replace("\"motion_enabled\":false","\"motion_enabled\":\"true\""),device,token,token,2);
    }
    @Test public void rejectsTypeCoercionAndFractionalSequence() {
        reject(ack().replace("\"seq\":2","\"seq\":2.5"),device,token,token,2);
        reject(ack().replace("\"live\":true","\"live\":\"true\""),device,token,token,2);
        reject(ack().replace("\"protocol\":1","\"protocol\":\"1\""),device,token,token,2);
    }
    @Test public void rejectsMalformedAndIncomplete() {
        reject("{}",device,token,token,2); reject("not json",device,token,token,2);
    }
    @Test public void refusesInvalidOutgoingHeartbeat() {
        for (long seq : new long[]{0,-1,0x100000000L}) {
            try { EspProtocol.heartbeat(token,seq); fail(); } catch (IllegalArgumentException expected) { }
        }
        try { EspProtocol.heartbeat("injection",1); fail(); } catch (IllegalArgumentException expected) { }
    }
}
