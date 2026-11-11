package com.argsecure.rastagmobility;

import org.junit.Test;
import static org.junit.Assert.*;

public class EspSensorStatusTest {
    private final String boot="0123456789abcdef0123456789abcdef";
    private String data(boolean configured,boolean verified,boolean fresh,boolean complete,int points,int age){return "{\"protocol\":2,\"boot\":\""+boot+"\",\"lidar\":{\"configured\":"+configured+",\"hardware_verified\":"+verified+",\"fresh\":"+fresh+",\"complete\":"+complete+",\"points\":"+points+",\"scan_age_ms\":"+age+"}}";}
    @Test public void inertBoardDoesNotImplySensorOrMappingReady(){EspSensorStatus s=EspSensorStatus.parse(data(false,false,false,false,0,-1),boot,1000);
        assertEquals("not_configured",s.state(1001));assertTrue(s.json(1001).contains("\"capture_available\":false"));assertFalse(s.json(1001).contains(boot));}
    @Test public void staleObservationBecomesUnavailable(){EspSensorStatus s=EspSensorStatus.parse(data(true,true,true,true,320,10),boot,1000);
        assertEquals("ready",s.state(1000));assertEquals("unavailable",s.state(7000));assertEquals("unavailable",s.state(999));}
    @Test public void everySensorRequirementMatters(){assertEquals("unverified",EspSensorStatus.parse(data(true,false,true,true,320,10),boot,1).state);
        assertEquals("no_data",EspSensorStatus.parse(data(true,true,false,true,320,10),boot,1).state);
        assertEquals("no_data",EspSensorStatus.parse(data(true,true,true,false,320,10),boot,1).state);
        assertEquals("no_data",EspSensorStatus.parse(data(true,true,true,true,0,10),boot,1).state);
        assertEquals("no_data",EspSensorStatus.parse(data(true,true,true,true,320,300),boot,1).state);}
    @Test(expected=IllegalArgumentException.class) public void rebootMismatchRejected(){EspSensorStatus.parse(data(false,false,false,false,0,-1),"different",1);}
    @Test(expected=IllegalArgumentException.class) public void impossiblePointCountRejected(){EspSensorStatus.parse(data(true,true,true,true,1025,0),boot,1);}
}
