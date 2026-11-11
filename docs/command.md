# Motion and OTA contract

## ESP HTTPS

All endpoints require the paired client certificate; the client validates the server CA, hostname and pinned SPKI. One Android foreground service owns `/v1/session` and its 7-second heartbeat lease. Movement has the stricter 250 ms independent expiry.

`GET /v3/motion` returns controller boot ID, uptime, sequence/run, mode, reason, requested/decision direction, relay/PWM/opto commanded state, scan/link freshness, profile, and four sector clearances. `stop_confirmed` is always false because no wheel feedback exists.

`POST /v3/motion` uses strict flat JSON, rejects duplicate/unknown fields, wrong boot/session, stale/out-of-order commands and excessive deadlines. Base fields are `boot`, `session`, `seq`, `operation`; non-stop requests also require `expires_at_ms` in ESP monotonic time.

- `drive`: `run`, `direction` (1 forward, 2 back, 3 left, 4 right), `intensity` (1–100).
- `jog`: `run`, `left_cdeg`, `right_cdeg`; relay and optos stay OFF.
- `stop`: base fields only. Safe and idempotent, even after lease expiry in the same session.
- `configure`: `verified:true`, `f_left/f_right`, `b_left/b_right`, `l_left/l_right`, `r_left/r_right`, `front_cdeg`, `stop_mm`. Requires stopped state, four distinct non-home bounded pairs, and a deadline at most 2 seconds ahead. Saved atomically in NVS. Does not activate motion.

Old `/v2/motion/arm` and `/v2/motion/drive` cannot bypass calibrated named directions. `/v2/lidar/frame` remains the RLS2 binary frame contract. `/v2/ota` supports authenticated app-only OTA with size/project/digest validation and boot rollback; never use raw flash backups as OTA input.

## Android IPC

Signature permission: `com.argsecure.permission.RASTAG_MOBILITY`. Send an explicit broadcast to `com.argsecure.rastagmobility.MobilityReceiver`, action `com.argsecure.rastagmobility.MOTION_REQUEST`, with `MobilityContract.PAYLOAD` and the trusted reply package. Use `MotionRequest.json(...)` from mobility-api for both internal AI planners (`source:ai`) and explicit user commands (`source:human`).

Payload: `version:1`, 32-hex `request_id`, `issued_elapsed_ms` from Android elapsedRealtime, `ttl_ms` ≤3000, `source`, `operation:drive|stop`, `direction:forward|back|left|right|stop`, `intensity`, `duration_ms` ≤3000. STOP uses zero intensity and duration. Requests require a current temporary operator enable except STOP. No raw GPIO, credentials, network addresses, or servo angles are exposed through this public request interface.

`MOTION_RESULT` reports admission (`queued`, rejected reason, duplicate), **not physical execution**. `MOTION_STATUS` broadcasts the public aggregate decision: request, decision, reason, source, configured/driving, monotonic publication time, and `physical_motion_verified:false`. Discard stale/future observations. Accepted requests are never automatically replayed. Stop/failure clears the active demand instead of leaving a backlog.
