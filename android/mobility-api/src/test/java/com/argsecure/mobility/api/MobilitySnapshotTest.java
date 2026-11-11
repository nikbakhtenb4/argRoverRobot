package com.argsecure.mobility.api;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.argsecure.mobility.api.LinkHealth.State.*;

public class MobilitySnapshotTest {
    private final String instance = "0123456789abcdef0123456789abcdef";
    private MobilitySnapshot live() { return new MobilitySnapshot(instance,3,11000,10000,CONNECTED); }
    @Test public void roundTripPreservesAuthenticatedAckAge() {
        MobilitySnapshot s = MobilitySnapshot.parse(live().json(),11010);
        assertEquals(CONNECTED,s.state(16999));
        assertEquals(DISCONNECTED,s.state(17000));
        assertEquals(SERVICE_UNAVAILABLE,s.state(19000));
    }
    @Test public void duplicateAndReorderedBroadcastsCannotRefreshStatus() {
        MobilitySnapshot previous = live();
        assertFalse(previous.isNewerThan(previous));
        assertFalse(new MobilitySnapshot(instance,2,11000,10000,CONNECTED).isNewerThan(previous));
        assertFalse(new MobilitySnapshot(instance,4,10999,10000,CONNECTED).isNewerThan(previous));
        assertTrue(new MobilitySnapshot(instance,4,12000,11500,CONNECTED).isNewerThan(previous));
    }
    @Test public void restartedServiceMustPublishNewerTimestamp() {
        assertTrue(new MobilitySnapshot("a"+instance.substring(1),0,11001,0,CONNECTING).isNewerThan(live()));
        assertFalse(new MobilitySnapshot("a"+instance.substring(1),20,10999,0,CONNECTING).isNewerThan(live()));
    }
    private void reject(String json,long now) {
        try { MobilitySnapshot.parse(json,now); fail("Bad status accepted"); } catch (RuntimeException expected) { }
    }
    @Test public void rejectsOldFutureAndMalformedMessages() {
        reject(live().json(),19000); reject(live().json(),10999);
        reject("{}",11000); reject("not-json",11000);
        reject(live().json().replace("\"serial\":3","\"serial\":3.5"),11000);
        reject(live().json().replace("\"version\":1","\"version\":\"1\""),11000);
    }
    @Test public void reportsMotionAndLidarWithoutClaimingMapping() {
        MobilitySnapshot moving=MobilitySnapshot.parse(new MobilitySnapshot(instance,3,11000,10000,CONNECTED,true,"live").json(),11000);
        assertTrue(moving.motionEnabled);assertEquals("live",moving.lidarState);
        reject(live().json().replace("\"motion_enabled\":false","\"motion_enabled\":\"true\""),11000);
        reject(live().json().replace("not_configured","ready"),11000);
        reject(live().json().replace("awaiting_lidar_and_odometry","mapping"),11000);
    }
    @Test public void missingAckNeverTurnsGreen() {
        assertEquals(DISCONNECTED,new MobilitySnapshot(instance,0,10000,0,CONNECTED).state(10000));
        reject(new MobilitySnapshot(instance,0,10000,10001,CONNECTED).json(),10000);
    }
}
