# Home Assistant cameras

## Contract

Add one independent Home Assistant connection alongside Frigate and ONVIF. The
user explicitly selects `camera.*` entities. Keep existing sessions, stores,
playback and settings intact. Do not expose or invoke services, alarm controls,
switches, PTZ, recording commands, microphone capture or talkback.

Connection uses a long-lived access token, stored with Android Keystore AES-GCM
in noBackupFilesDir. HTTPS is required except private/loopback/link-local IPs and
`.local` hosts after explicit local-HTTP confirmation. Never log credentials or
pass the HA access token to JavaScript, thumbnails URLs or external origins.

Home cards initially show a single camera image, labelled as an image, not live.
Live playback starts only on user request. Read camera capabilities and use HLS
or receive-only WebRTC. Unsupported/failed live playback remains visibly an
image, not fake live. Blink clips and event history are outside this first slice.

## Implementation

1. `HomeAssistantClient.kt`: bounded read-only HTTP, authenticated WebSocket,
   typed camera-capability/stream/WebRTC operations and strict URL validation.
   JVM MockWebServer tests first: camera filtering, auth, redirects, malicious
   endpoints, auth errors and camera-only WebSocket commands.
2. `HomeAssistantStore.kt`: encrypted, atomic optional connection and selected
   cameras. Instrumented encrypted round-trip and disconnect checks.
3. `HomeAssistantCameraView.kt`, `assets/ha-camera.html`: on-demand camera-only
   playback. WebRTC uses browser media engine without microphone permission;
   signaling stays native. Stop sockets, players, workers and audio offscreen,
   on background, lock, navigation and destruction. Bounded timeouts, no busy
   polling or automatic battery-camera wakeup.
4. `HomeAssistantActivity.kt`: server/token form, explicit local HTTP consent,
   camera multi-selection, save/cancel/disconnect. Respect biometric lock.
5. `MainActivity.kt`, manifest and localized resources: source choice, settings
   entry, mixed-source home and full-screen presentation without leaving app.
   Do not ship unrelated dirty web/settings changes or silently publish.
6. Build unit tests, debug APK, instrumentation APK and lint. Run fixture-based
   Android tests and inspect phone-sized screenshots where an emulator is
   operational. Real Ring/Blink playback is not proven by fixtures.

## References

- https://developers.home-assistant.io/docs/api/rest/
- https://developers.home-assistant.io/docs/api/websocket/
- https://github.com/home-assistant/core/blob/dev/homeassistant/components/camera/__init__.py
- https://github.com/home-assistant/core/blob/dev/homeassistant/components/camera/webrtc.py
- https://github.com/home-assistant/frontend/blob/dev/src/data/camera.ts
- https://www.home-assistant.io/integrations/blink/
- https://www.home-assistant.io/integrations/ring/

## Rollback and evidence

Disconnect deletes only the Camnory Home Assistant connection and selection;
never removes entities or changes the HA server. No migration to Frigate/ONVIF
storage. User must revoke the long-lived token in HA if desired. Real server
camera availability, cloud limitations and locked-device behavior require
separate acceptance evidence; do not present a successful build as device proof.

## Validation recorded 2026-10-16

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: successful.
  Five focused HA client tests passed, including auth rejection, camera-only
  filtering, camera-only signaling and refusing redirects/external HLS URLs.
- Fresh isolated `Camnory_HA_QA` emulator on port 5572: final Android run passed
  12 tests (8 HA, connection-form regression, 3 recorded-event regressions).
  HA checks cover encrypted persistence, selection, coexistence with Frigate
  and ONVIF, no automatic live session, no unsupported-camera fake live badge,
  receive-only WebRTC SDP, background teardown, fullscreen return and narrow
  column controls. MockWebServer supplies camera fixtures; these are not real
  Ring/Blink recordings or device evidence.
- Earlier ONVIF home regression run also passed; its opt-in physical-camera
  case was skipped. No real camera, HA server, alarm or siren was contacted.
- Rendered screenshots inspected: `ha-connection.png`, `ha-camera-selection.png`,
  `ha-home.png`, `ha-fullscreen.png`. Fixed system-bar overlap, fullscreen
  controls clipping, an image fallback race and initial narrow-control overflow.
- The original emulator was left untouched. The isolated QA emulator is stopped
  after verification. Existing dirty web/settings edits remain untouched.
- Pending: real Home Assistant/Ring authentication, video/audio playback and
  local/remote-network compatibility. HLS URL negotiation is fixture-tested;
  actual HLS/Ring video decoding has not been proved. Blink clip history remains
  outside this change.

## Test release 0.2.68

User approved publication for phone acceptance testing. Android versionCode 91,
release tag `v0.2.68-kotlin-debug`, including HA cameras and same-moment thumbnail
fullscreen controls. This is a prerelease, not a claim of verified real Ring
playback. Rollback: remove only the local HA connection, or reinstall the prior
0.2.67 test build with the Android version downgrade procedure if necessary.
No migration of existing Frigate/ONVIF sessions. Unrelated dirty web/settings
changes are excluded from the commit. See GitHub release for publication status.
