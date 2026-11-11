package com.argsecure.mobility.api;
import org.junit.Test;
import static org.junit.Assert.*;
public class MotionSnapshotTest {
    String sample(){return new MotionSnapshot("forward","stop","obstacle","human",1000,true,false).json();}
    void reject(String raw,long now){try{MotionSnapshot.parse(raw,now);fail();}catch(RuntimeException expected){}}
    @Test public void separatesRequestedFromControllerDecision(){MotionSnapshot s=MotionSnapshot.parse(sample(),1000);assertEquals("forward",s.requested);assertEquals("stop",s.decision);assertEquals("obstacle",s.reason);assertFalse(s.driving);}
    @Test public void rejectsStaleFutureAndPhysicalClaims(){reject(sample(),5000);reject(sample(),999);reject(sample().replace("\"physical_motion_verified\":false","\"physical_motion_verified\":true"),1000);}
    @Test public void rejectsCoercionsUnknownDirectionAndSource(){reject(sample().replace("\"driving\":false","\"driving\":\"false\""),1000);reject(sample().replace("forward","anywhere"),1000);reject(sample().replace("human","stranger"),1000);}
    @Test public void boundedCommandBuilder(){assertTrue(MotionRequest.json("00000000000000000000000000000001","ai","forward",20,3000,1000).contains("\"source\":\"ai\""));try{MotionRequest.json("00000000000000000000000000000001","ai","forward",20,3001,1000);fail();}catch(IllegalArgumentException expected){}}
}
