package com.argsecure.mobility.api;

/** Small in-memory view of the authenticated link. No network or disk work on the UI thread. */
public final class LinkHealth {
    public enum State { CONNECTING, CONNECTED, DISCONNECTED, UNPAIRED, PAIRING_ERROR, INACTIVE, SERVICE_UNAVAILABLE }
    private State state = State.INACTIVE;
    private boolean visible;
    private long generation, acknowledged;

    public synchronized void setVisible(boolean value) {
        if (visible == value) return;
        visible = value;
        generation++;
        acknowledged = 0;
        state = value ? State.CONNECTING : State.INACTIVE;
    }
    public synchronized boolean isVisible() { return visible; }
    public synchronized long generation() { return generation; }

    public synchronized boolean update(String value, long ackElapsedMs, long requestGeneration) {
        // An old request completing after pause/resume must not resurrect a green badge.
        if (requestGeneration != generation) return false;
        acknowledged = ackElapsedMs;
        if (!visible) state = State.INACTIVE;
        else if ("connected".equals(value)) state = State.CONNECTED;
        else if (("discovering".equals(value) || "connecting".equals(value))) state = State.CONNECTING;
        else if ("unpaired".equals(value)) state = State.UNPAIRED;
        else if ("pairing_error".equals(value)) state = State.PAIRING_ERROR;
        else state = State.DISCONNECTED;
        return true;
    }

    public synchronized State state(long nowElapsedMs) {
        if (state != State.CONNECTED) return state;
        long age = nowElapsedMs - acknowledged;
        return acknowledged > 0 && age >= 0 && age < MobilityContract.LEASE_MS
                ? State.CONNECTED : State.DISCONNECTED;
    }
}
