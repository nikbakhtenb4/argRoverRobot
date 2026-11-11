# Integration into the existing Rastag / PhoenixScada application

The independent mobility and mobility-api projects above build standalone. This directory contains the actual updated main-app integration source files and intent regression test, intended to overlay the existing `rover` module in PhoenixScada. It is not a second standalone robot app: the rest of the existing face, voice and Phoenix modules and proprietary model assets are deliberately not duplicated here.

The root Phoenix settings already include `:mobility` and `:mobility-api`; Rover depends on mobility-api and holds the signature permission and package visibility query. Overlay `src` onto the existing Rover module and use versionCode 920 / versionName 8.1.0-rastag.79. Build with `:rover:testRobotDebugUnitTest :rover:assembleRobotDebug`.

`RoverHardwareClient` subscribes to `MOTION_STATUS`; `RoverApplication.motionSnapshot()` exposes it. `RoverActivity` places `RoverMotionBadge` below the LiDAR card inside the existing measured layout, retaining safe display bounds and system keyboard behavior. Tapping it opens the independent movement panel. The settings menu also exposes calibration/control.

`RoverLiveBackend` routes exact current-user movement imperatives through `RoverMotionBridge` before knowledge queries. Existing input admission, stale-turn cancellation, model/voice selection and public identity are preserved. `RoverMotionIntent` rejects questions/quotes/conditional/coding phrases. Internal AI planners use the same documented `MotionRequest` IPC with source `ai`; this release does not launch an autonomous navigation planner.
