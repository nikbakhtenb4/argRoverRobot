package com.argsecure.mobility.api;

/** Versioned, signature-protected IPC. Never use broadcasts for a motor control loop. */
public final class MobilityContract {
    public static final int VERSION = 1;
    public static final String PACKAGE = "com.argsecure.rastagmobility";
    public static final String PERMISSION = "com.argsecure.permission.RASTAG_MOBILITY";
    public static final String RECEIVER = PACKAGE + ".MobilityReceiver";
    public static final String SERVICE = PACKAGE + ".MobilityService";
    public static final String START = PACKAGE + ".START";
    public static final String QUERY = PACKAGE + ".QUERY";
    public static final String COMMAND = PACKAGE + ".COMMAND";
    public static final String STATUS = PACKAGE + ".STATUS";
    public static final String RESULT = PACKAGE + ".RESULT";
    // Read-only scans, package-targeted and signature-protected. No motor control over IPC.
    public static final String LIDAR = PACKAGE + ".LIDAR";
    public static final String FRAME = "frame";
    public static final String RECEIVED_ELAPSED = "received_elapsed_ms";
    public static final String ROUND_TRIP = "round_trip_ms";
    public static final String PAYLOAD = "payload";
    public static final String REPLY_PACKAGE = "reply_package";
    public static final long LEASE_MS = 7000;
    public static final long STATUS_MAX_AGE_MS = 8000;
    private MobilityContract() { }
}
