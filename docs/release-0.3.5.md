# Camnory 0.3.5 TV preview

## User-approved presentation

Option 2: a main camera on the left and up to three selectable alternatives
on the right, paged as necessary. Full-screen and camera settings are separate
actions. The selected camera is remembered. A right-side settings panel exposes
existing Frigate feature toggles and source-native controls; unsupported native
controls remain disabled. ONVIF hardware information and profiles can be read
on demand. No new light, alarm or PTZ protocol is introduced.

The header gives access to recent Frigate events and administration. Event
playback uses the existing VOD URLs and same-origin authenticated media client.
No ONVIF or Home Assistant event history is implied.

## Administration

- Servers: edit the encrypted TV copy of Frigate/HA connection details, masked
  tokens with explicit reveal, optional local routes, read-only tests and removal
  from the TV after confirmation. Pairing and manual provider setup remain.
- Cameras: visibility, order, primary selection, per-camera settings and ONVIF
  connection editing/removal. Preserve hidden/order keys when server URLs change.
- Picture and sound: main/economy Frigate stream, 360/720/1080 preview request,
  muted Frigate start and optional Frigate video crop. These are viewer settings,
  not changes to camera encoding, native sound detection or PTZ capability.
- Appearance: TV-only black/charcoal background, text size and five accent colors.
- Diagnostics: app version, network transport, actual last-used local/external
  addresses and explicit safe server tests. No automatic hardware commands.

Adding a provider manually merges it with the existing imported TV configuration,
rather than erasing other sources. TV appearance is separate from mobile prefs.
Pairing is still an explicit copy, not continuous phone synchronization.

The launcher shield is re-exported from the original supplied brand artwork:
its visible bounds are centered in 320x320 with safe circular-mask margins.
Rectangular TV branding and mobile launcher icons are unchanged.

## Verification and limits

Build app and instrumentation APKs, run unit tests and lint, verify signer,
inspect native rendered home/admin/panel images, and run TV workspace/banner
instrumentation tests on the physical Smart_TV_Pro. Home rendering uses the
existing test photo assets, never configured live cameras. Administration
navigation uses the real activity and DPAD. The camera panel capture uses fixture
controls; no siren, light, PTZ, save or remove action is invoked by these tests.
Only metadata may be requested from the configured Frigate server.

Real live-stream decoder quality, event playback and hardware controls remain
user validation items; the UI tests are not proof of these behaviors. TV labels
are French/English, with English fallback in the other app locales, matching the
existing TV preview. No emulator was launched.

Publication is a signed GitHub prerelease on kotlin-native, not Play Store.
Verify the public APK hash and installed TV version before reporting delivery.

2026-10-17 gate results: app and instrumentation assembly succeeded, lint has
zero errors (existing warnings remain), 174 unit cases with 173 passed and one
ignored, and all three selected instrumentation tests passed on Smart_TV_Pro.
Native home layouts at 1280x720 and 1920x1080, real administration and fixture
side panel captures were inspected. Installed TV version is 0.3.5, code 128.
The navigation test asserts that it does not create, delete or rewrite the
encrypted TV configuration during its own run. No prior configuration is restored.
No live camera or hardware control was exercised. The source artwork export
reported bounds x128..419, y208..508; device pixel tests verify centered margins
and containment in the circular launcher mask.
