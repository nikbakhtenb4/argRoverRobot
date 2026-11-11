package com.argsecure.mobility.api;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.argsecure.mobility.api.LinkHealth.State.*;

public class LinkHealthTest {
    private LinkHealth foreground() {
        LinkHealth status = new LinkHealth();
        status.setVisible(true);
        return status;
    }
    @Test public void startupWaitsForAuthenticatedAcknowledgement() {
        LinkHealth status = foreground();
        assertEquals(CONNECTING, status.state(10000));
        status.update("discovering", 0, status.generation());
        assertEquals(CONNECTING, status.state(10000));
        status.update("connected", 10000, status.generation());
        assertEquals(CONNECTED, status.state(10000));
    }
    @Test public void stalledWorkerCannotKeepBadgeGreenPastLease() {
        LinkHealth status = foreground();
        status.update("connected", 10000, status.generation());
        assertEquals(CONNECTED, status.state(16999));
        assertEquals(DISCONNECTED, status.state(17000));
        assertEquals(DISCONNECTED, status.state(60000));
        status.update("connected", 60001, status.generation());
        assertEquals(CONNECTED, status.state(60001));
    }
    @Test public void missingAndFutureAcknowledgementsCannotAppearConnected() {
        LinkHealth status = foreground();
        status.update("connected", 0, status.generation());
        assertEquals(DISCONNECTED, status.state(1));
        status.update("connected", 10000, status.generation());
        assertEquals(DISCONNECTED, status.state(9999));
    }
    @Test public void failedHeartbeatImmediatelyOverridesRecentSuccess() {
        LinkHealth status = foreground();
        status.update("connected", 10000, status.generation());
        status.update("disconnected", 10000, status.generation());
        assertEquals(DISCONNECTED, status.state(10001));
        status.update("unexpected", 10000, status.generation());
        assertEquals(DISCONNECTED, status.state(10002));
    }
    @Test public void resumeRejectsLateSuccessAndFailureFromEarlierActivity() {
        LinkHealth status = foreground();
        long oldGeneration = status.generation();
        status.update("connected", 10000, oldGeneration);
        status.setVisible(false);
        assertEquals(INACTIVE, status.state(10001));
        status.setVisible(true);
        assertEquals(CONNECTING, status.state(10002));
        assertFalse(status.update("connected", 10003, oldGeneration));
        assertFalse(status.update("disconnected", 10004, oldGeneration));
        assertEquals(CONNECTING, status.state(10004));
        assertTrue(status.update("connected", 10005, status.generation()));
        assertEquals(CONNECTED, status.state(10005));
    }
    @Test public void backgroundCannotPublishConnected() {
        LinkHealth status = foreground();
        status.setVisible(false);
        status.update("connected", 10000, status.generation());
        assertEquals(INACTIVE, status.state(10001));
    }
    @Test public void overlappingActivitiesDoNotResetLiveConnection() {
        LinkHealth status = foreground();
        long generation = status.generation();
        status.update("connected", 10000, generation);
        status.setVisible(true);
        assertEquals(generation, status.generation());
        assertEquals(CONNECTED, status.state(10001));
    }
    @Test public void configurationFailuresRemainDistinctFromConnectionProgress() {
        LinkHealth status = foreground();
        status.update("unpaired", 0, status.generation());
        assertEquals(UNPAIRED, status.state(10000));
        status.update("pairing_error", 0, status.generation());
        assertEquals(PAIRING_ERROR, status.state(10000));
    }
}
