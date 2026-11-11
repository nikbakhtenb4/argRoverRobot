package com.argsecure.rastagmobility;
import com.argsecure.mobility.api.MotionRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class MotionAuthorityTest {
    String req(int id,String source,String dir,int duration,long now){return MotionRequest.json(String.format("%032x",id),source,dir,dir.equals("stop")?0:20,duration,now);}
    @Test public void manualDeadmanCannotBeRevivedAfterExpiry(){
        MotionAuthority a=new MotionAuthority();long run=a.beginManual("drive",1,20,1000);
        assertTrue(a.takeStop());assertNotNull(a.current(1199));a.renew(run,1200);assertNull(a.current(1200));
        a.renew(run,1201);assertNull(a.current(1201));assertTrue(a.takeStop());
    }
    @Test public void failureRequiresNewPress(){
        MotionAuthority a=new MotionAuthority();long run=a.beginManual("drive",1,20,1000);a.failed(run);a.renew(run,1010);
        assertNull(a.current(1010));assertTrue(a.beginManual("drive",1,20,1020)>run);
        a.release(run);assertNotNull(a.current(1021));
    }
    @Test public void remoteLockedByDefaultAndStopAlwaysAllowed(){
        MotionAuthority a=new MotionAuthority();assertEquals("remote_control_locked",a.external(req(1,"ai","forward",3000,1000),1000));
        a.beginManual("jog",10000,10500,1000);assertEquals("queued",a.external(req(2,"human","stop",0,1000),1000));assertNull(a.current(1000));
    }
    @Test public void prioritiesAndNoReplay(){
        MotionAuthority a=new MotionAuthority();a.allowRemote(true,1000);
        assertEquals("queued",a.external(req(1,"ai","forward",3000,1000),1000));
        assertEquals("queued",a.external(req(2,"human","back",3000,1000),1000));
        assertEquals("manual_priority",a.external(req(3,"ai","left",3000,1000),1000));
        assertEquals("duplicate_request",a.external(req(2,"human","back",3000,1000),1000));
        a.beginManual("drive",4,10,1000);assertEquals("manual_priority",a.external(req(4,"human","back",3000,1000),1000));
        assertEquals(4,a.current(1000).a);
    }
    @Test public void finiteRemoteDurationAndPermissionExpiry(){
        MotionAuthority a=new MotionAuthority();a.allowRemote(true,1000);
        assertEquals("queued",a.external(req(1,"ai","forward",100,1000),1000));assertNull(a.current(1100));
        assertEquals("queued",a.external(req(2,"ai","forward",3000,60999),60999));assertNull(a.current(61000));
        assertFalse(a.remoteAllowed(61000));
    }
    @Test public void staleFutureMalformedAndOversizedRejected(){
        MotionAuthority a=new MotionAuthority();a.allowRemote(true,1000);
        assertEquals("expired_request",a.external(req(1,"human","forward",3000,1000),4000));
        assertEquals("expired_request",a.external(req(2,"human","forward",3000,1001),1000));
        assertEquals("invalid_request",a.external("{}",1000));
        assertEquals("invalid_request",a.external(req(3,"human","forward",3000,1000).replace("\"intensity\":20","\"intensity\":20.5"),1000));
        assertEquals("invalid_request",a.external(req(4,"human","forward",3000,1000).replace("\"duration_ms\":3000","\"duration_ms\":4000"),1000));
        assertNull(a.current(1000));
    }
}
