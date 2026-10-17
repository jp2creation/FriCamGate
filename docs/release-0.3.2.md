# Camnory 0.3.2 TV preview

## Changes

- TV dashboard: two columns, at most four cameras per page, compact header,
  overlay names and pairing moved into TV settings.
- Frigate TV previews request 720px height. Fullscreen HLS selects an advertised
  main/high stream separately from the mobile low-bandwidth preference.
  The 640px MJPEG fallback is explicitly marked as reduced quality.
- Launcher uses an opaque 320x180 raster of the supplied logo rather than the
  platform splash drawable. Application and TV activity both declare the banner.
- Remove the redundant refresh icon from the phone notification history;
  pull-to-refresh remains. TV refresh is retained for remote control use.
- Optional local address for Frigate and Home Assistant, alongside the existing
  canonical server address. Existing settings remain valid with no local route.

## Local route contract

The user supplies a private numeric IPv4 URL and an IPv4/CIDR home network.
"Use this network" fills the current Wi-Fi/Ethernet subnet. This matches the
network range, not an authenticated Wi-Fi identity; use only trusted networks.
Cellular, VPN and other network ranges use the original server URL directly.

On a matching network, a read-only authenticated local health request is bound
to that Android network (Frigate `/api/version`, HA `/api/`). No redirects are
followed. Timeout is 1.8 seconds. Local success is cached up to 30 seconds,
failure for 5 seconds; a network change invalidates the decision on the next
request. This is request-time routing, not a restart of already-open streams.
Existing player reconnect behavior establishes a new route after disconnection.

Origin checks run before routing. Only the explicitly configured canonical
origin/path can be rewritten to the explicitly configured private endpoint.
No unrelated URL receives credentials. Local HTTP is not encrypted; the form
warns about this. TLS validation is never disabled.

Only failed GET/HEAD reads may fall back automatically to the external server.
Mutations and WebSocket upgrades are never replayed across addresses. No real
light, alarm, siren or PTZ is used in validation. Direct ONVIF settings are unchanged.
Camera identity/order/hidden keys stay based on the canonical URL, not routing.
Optional routes are encrypted with their provider configuration and transferred
with TV pairing. Pairing is still a one-time copy, not continuous synchronization.

## Release gates and remaining device checks

Run unit tests (routing selection, path/query safety, failed local probe,
network change, read fallback, no command replay and transfer compatibility),
assemble app and instrumentation APKs, run lint and verify signer/public hash.
Execute only the banner pixel test on the real TV; it does not launch camera
views or modify saved connections. No emulator is needed.

Actual local/external server handover and main-stream decoder quality require
the user's configured endpoints and device playback validation. No 4K quality
claim is made. Publish as a GitHub prerelease, not a Play Store rollout.
Rollback is reinstalling the previous signed APK with version downgrade enabled;
optional route fields are backward-compatible with the previous provider files.

Verified on 2026-10-17: build, app/instrumentation assembly and lint succeeded;
169 unit cases, 168 passed, one skipped, no failures. The isolated banner pixel
test passed on the physical Smart_TV_Pro (one test); installed version 0.3.2,
code 125. Test package removed afterwards. Signer matches the existing app.
No emulator, saved connection modification or real camera actuation was used.
