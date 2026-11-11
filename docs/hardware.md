# Electrical interface and controller behavior

This follows ARG Rover commit ed3ff5c34af4a63b27d592cde78a7c66bdfc2689 and the owner-confirmed wiring. Older diagrams showing a third servo, GPIO27 opto, or MPU6050 are not the wiring authority for this release.

| Signal | ESP32 pin / behavior |
|---|---|
| Tilt servo 1 / 2 | GPIO18 / GPIO19; 50 Hz, 500–2500 us mapping |
| Home angles | 100 / 105 degrees; allowed profiles 80–120 degrees |
| Opto 1 / 2 | DAC GPIO25 / GPIO26; requested nominal 2630 mV when enabled |
| Opto 3 / 4 | GPIO23 / GPIO13; Hi-Z enabled, output LOW disabled |
| Hoverboard relay | GPIO4, **active LOW**; HIGH is OFF |
| Delta-2A LiDAR receive | GPIO27, UART2, 230400 baud, 8N1; RX only |
| Maintenance console | USB UART0 115200 baud |

Servos tilt the hoverboard gyro boards; they do not directly set wheel speed. The firmware does not fabricate IMU/encoder feedback. DAC voltage depends on supply/calibration. External circuitry must hold the active-low relay OFF during reset/boot before software takes ownership; verify relay polarity and cutoff on the actual board. A software STOP is not a certified physical emergency stop. LiDAR detects returns in its scan plane, not drop-offs or every transparent/low obstacle.

The 10 ms control loop owns outputs. A drive begins with relay OFF and 1.5 seconds at home, then enables all four original optos and the relay. Tilt slews at 10 degrees/second. Intensity scales the calibrated tilt, not a measured velocity. Stop cuts relay/optos/PWM rather than moving a powered hoverboard toward home.

Every movement/jog command expires within 250 ms without renewal. Loop stalls, link loss, missing/faulty scans, obstacle clearance, OTA, or output errors inhibit motion. Faulted runs cannot automatically resume; a new explicit action is required. Output faults latch until reboot. Unchanged outputs are not rewritten at idle.

The ESP buffers UART data, validates Delta-2A frames, assembles full revolutions, and sends translated RLS2 scans to Android. The local interlock requires a valid return in every 2-degree bin of the required sector: front/reverse sector for translation, full circle for turns. Missing coverage is unknown and inhibits motion. `front_cdeg` aligns scan angle to the chassis; stop distance is calibrated in 300–3000 mm, default 400 mm. A 400 mm band above that distance reduces tilt. This is a conservative sector policy, not a fitted robot footprint or certified collision avoidance system.

Read-only USB diagnostics: `LIDAR?` and `MOTION?`. The retired generic two-wheel controller is used only by legacy regression tests; it is not compiled into the application.
