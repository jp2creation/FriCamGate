# ONVIF home integration

Scope: display manually registered ONVIF cameras on the shared home screen,
including without a Frigate session. Keep Frigate credentials, playback and
notifications separate. No discovery, recording history, PTZ, microphone,
alarm, relay or camera configuration writes.

Use the existing read-only ONVIF client and Media3 RTSP/TCP player. A card
connects only while visible and the activity is unlocked and resumed. Release
the player and cancel pending work on detach, background or navigation.
Retry transient failures a bounded number of times, then offer manual retry.
Remember the selected profile in the encrypted camera store, never the RTSP URL.

Camera management may update the locally stored name, address and credentials
after a successful read-only connection, without modifying the camera itself.
Fullscreen uses the same stored camera ID and selected profile, not credentials
in an Intent. App lock must continue to protect both screens.

Acceptance: encrypted legacy records remain readable; direct cameras appear on
home without Frigate; provider sessions stay independent; navigation/background
releases direct players; selected profiles persist; fullscreen and retry work;
only GetSystemDateAndTime/GetServices/GetProfiles/GetStreamUri are sent. Run
unit, Android instrumentation and lint checks. Hardware checks are limited to
Devant and must distinguish rendered video from audible phone output.

## Verification, 2026-10-16

- Unit tests, APK/test APK compilation and lint pass.
- Android regression run: 25 runner entries, including three opt-in tests
  skipped without their arguments; remaining 22 pass.
- Focused home checks also cover 90/144/296 dp control widths, encrypted profile
  round trips, no Frigate session mutation, navigation/background release and no
  automatic authentication retries after HTTP 401.
- Opt-in Devant hardware check passes: real RTSP video advances on the shared
  home screen without a Frigate token; backgrounding releases the home player;
  fullscreen video keeps playing after landscape rotation. Screenshots inspected.
- Hardware credentials are passed through temporary private test files, removed
  afterward, and camera-store/preferences snapshots are restored after each test.
- Frigate remains healthy. No configuration, siren, relay or alarm operation is
  available through this ONVIF client.

Limits: manual Media1/H264 cameras only. Physical phone audio and large
simultaneous multi-camera grids require device acceptance checks. Discovery,
camera event subscriptions and recording history are not part of this change.
