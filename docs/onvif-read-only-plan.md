# Direct ONVIF cameras, first slice

Scope: manually register a camera endpoint, read Media1 profiles and a unicast
RTSP URI for an H264 profile, then view it with Media3 RTSP. Keep Frigate sessions and code paths
unchanged. First authorized hardware check: Devant only.

Hard exclusion: no alarm, siren, relay, digital output, PTZ, event subscription,
configuration or Set operation. The transport exposes only a closed set of Get
operations. Opening a unicast RTSP stream is the only playback operation.

Implementation: use existing OkHttp and a structured XML pull parser for a small
read-only SOAP adapter, and the official Media3 RTSP engine for playback. Audited
com.seanproctor:onvifcamera 1.8.3/2.0.4 use a Media2-only service lookup; the newer
stable 2.2.1 requires Kotlin 2.4, incompatible with this project's Kotlin 2.0.21.
Do not upgrade the app toolchain or silently adapt camera settings to fit them.

Security: WS-Security nonce/digest, bounded responses, no DTD, endpoints and stream
URIs restricted to the registered host, no logs containing credentials, no HTTP
redirects. Camera passwords stored with Android Keystore AES-GCM in no-backup
storage. No camera password is copied from Frigate into persistent app settings
without the user adding the camera.

Non-goals: network scan, Media2-only devices, HTTP-Digest-only devices, recordings,
microphone/talkback, camera mutations and notifications. Errors must be explicit.
Tests: mock SOAP requests enforce the Get-only boundary, malformed/hostile XML
and credential encoding tests, Android encrypted persistence and playback checks.
Hardware evidence must distinguish profiles/URI discovery from a rendered frame.

Media3 is aligned at 1.5.1: its RTSP removeUserInfo uses encoded authority, fixing
1.4.1 splitting an encoded password containing @ into an invalid host. Recheck
Frigate recording/live regression tests with this shared-engine upgrade.

References: https://www.onvif.org/specs/srv/media/ONVIF-Media-Service-Spec-v210.pdf
and https://developer.android.com/media/media3/exoplayer/rtsp

## Acceptance evidence, 0.2.61

- Unit tests cover the exact four allowed Get operations, WS-Security digest,
  XML entity rejection, endpoint host checks and reserved credential characters.
- Android tests cover encrypted persistence, tamper rejection and unchanged
  Frigate session preferences when opening the independent camera manager.
- Devant hardware check passed: Media1 profiles and stream URI read, first H264
  frame rendered directly over RTSP/TCP, player left initial buffering and its
  playback position advanced. Screenshot inspected; no NVR in the media path.
- Shared Media3 upgrade: real Frigate recording rendered and advanced, and manual
  pause held its position. Runtime, notification and branding regressions passed.
- Temporary credential input used for hardware testing was deleted from both
  the emulator private files and host temporary storage. It was not saved as a
  camera registration or committed. No configuration writes or alarm calls.

Initial limitations: manual camera address; Media1/H264 and WS-Security only;
camera LAN or VPN access required. No discovery scan, Media2-only/HTTP-Digest-only
camera guarantee, recording timeline or talkback. Direct sound is optional and
actual phone audibility remains to be checked. Frigate remains a separate entry.
