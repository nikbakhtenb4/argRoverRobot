# ARG Rover: unified motion, LiDAR and OTA

One ESP-IDF firmware replaces the earlier standalone Arduino servo sketch. It retains the original ARG Rover electrical interface while adding calibrated direction profiles, local obstacle interlocks, pinned mutual TLS, and dual-slot OTA rollback. The companion Android foreground service owns the controller session. User and internal AI requests share a bounded signature-protected IPC interface.

**Physical direction calibration is still required.** The original four direction presets were identical. Shipping profiles deliberately remain unverified: no hoverboard power or servo motion on boot. Software tests do not certify wheel motion, braking distance, balance, or physical emergency-stop hardware.

## Build and checks

Open `ARG-Rover.code-workspace` in VS Code. It selects the actual ESP-IDF project
folder, so the PlatformIO debug adapter runs beside its `platformio.ini`.
Generate the local IntelliSense database with
`pio run --project-dir firmware/rastag_link --target compiledb`.
The three upstream debug launch modes are retained. Debugging requires the
configured hardware debug probe; a USB serial cable alone does not provide JTAG.
Generated Arduino include paths and another developer's absolute drive paths are
replaced by the current ESP-IDF compile database and workspace-relative paths.

```sh
python -m pip install platformio cryptography pyserial
python -m platformio run --project-dir firmware/rastag_link
python tools/test_arg_rover.py
python tools/test_rastag_firmware.py
python tools/test_rastag_ota.py
cd android
./gradlew :mobility:testDebugUnitTest :mobility-api:test :mobility:assembleDebug
```

Host C tests need GCC/Clang with UBSan (or the local Zig compiler). Android needs JDK 17+, SDK 34, and an SDK path via `ANDROID_HOME` or an untracked `local.properties`. No secrets, prebuilt firmware, device backups, APKs, or third-party face/voice models are included.

## Deploy

On an already paired Windows workstation, use the existing DPAPI identity:

```sh
python tools/rastag_ota.py status
python tools/rastag_ota.py upload firmware/rastag_link/.pio/build/esp32dev/firmware.bin --output artifacts/ota-result.json
```

Only the application image is uploaded over pinned mTLS; no USB erase, repartition, re-pairing, or credential replacement. A failed acknowledgement must be resolved by reading status, never by automatically resending firmware. Boot readiness and image identity are checked, followed by LiDAR advancement and de-energized movement state.

Install the Android mobility APK signed with the same application certificate as the main Rastag app. Pairing stays in private app storage. The service is independent of the face/voice application.

## Control and display

- Hold a direction button in the movement panel; releasing, losing focus, a stale request, obstacle, or missing coverage stops the command.
- Calibrate the four direction pairs explicitly; the isolated servo jog keeps the hoverboard relay and optos off. Relay-off jog cannot prove actual travel direction. Verify real direction and cutoff on an appropriate test rig before marking profiles verified.
- The operator may enable voice/AI requests for 60 seconds. Each external request lasts at most 3 seconds. Human commands outrank AI; manual holds outrank both; STOP takes precedence.
- The main screen shows requested direction, controller decision, and stop/rejection reason. These are **command decisions**, not measured velocity or proof of physical stopping.
- Explicit Persian commands are routed from the current authenticated user utterance, with no automatic retry. Questions, quotes, negations, and programming requests do not trigger movement.

See [hardware](docs/hardware.md), [protocol](docs/command.md), and [main-app integration](android/rover-integration/README.md). Full SLAM/autonomous navigation requires odometry/localization and is not implemented by this motion release. Existing map definitions and live scans are retained.
