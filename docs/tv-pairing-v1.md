# Android TV Preview: Local Configuration Transfer

## Contract

- One application ID and signer, separate TV launcher activity.
- Remote-friendly camera selection and live playback using existing source readers.
- TV displays a two-minute QR invitation while its pairing page is foreground.
- Phone scans, explicitly authorizes sharing connection credentials, then transfers over TLS.
- QR contains only a local IPv4 endpoint, ephemeral certificate pin and random pairing secret.
- Receiver verifies the secret before reading a bounded payload; one successful upload consumes the invitation.
- TV displays a summary and asks for explicit acceptance before saving anything.
- Imported configuration is one atomic AES-GCM file protected by an Android Keystore key.
- Transfer includes server/camera connections, display order and hidden cameras. Device UI, notifications,
  biometrics, chat, media, alarm states and commands are never transferred or executed.
- No automatic ongoing synchronization. Re-pairing explicitly replaces the TV configuration.

## Boundaries and Failure Behavior

- Scanned endpoints must be numeric private IPv4 addresses, with no arbitrary URL or DNS resolution.
- TLS certificate is pinned to the QR invitation; no system-wide trust changes or redirect following.
- Listener is bound to the selected local interface, closed on background, expiry or successful receipt.
- Invalid auth, pin mismatch, oversized data and malformed schemas fail without storing credentials.
- Source data never enters logs, QR codes, external files, clipboard, backups or intent extras.
- Initial launch after failed transfer continues to use the previous atomic configuration.
- Foreground pairing is protected against screenshots; scanner does not save barcode images.
- A person who scans the physical invitation is authorized to submit a proposal, not to overwrite settings silently.

## Verification / Release Gate

- Pure codec, URL restriction, malformed input and size-limit tests.
- Local TLS tests with dummy credentials: success, wrong pin, bad secret, single-use, close and expiry.
- Android storage/remote UI instrumentation compiled separately from execution evidence.
- Build, unit tests, lint, unchanged signing certificate and public APK hash verification.
- Publish as a TV preview, not as Play Store TV certification or a phone-to-TV field test.
- No emulator launch and no real siren, light or PTZ commands during validation.

## Preview Scope / Remaining Device Gates

- The same APK has a separate Leanback launcher; phone entry stays unchanged.
- Frigate mosaic uses snapshots; opening a camera attempts native HLS (muted by default) with MJPEG fallback.
  Fallback MJPEG has no audio. ONVIF and HA reuse the source-owned players and availability handling.
- No TV timeline, chat, advanced camera settings, automatic synchronization, device revocation or cloud account yet.
- Pairing UI is localized in French and English; other supported locales use English for these new screens.
- LAN must provide private IPv4 connectivity between phone and TV (no guest-client isolation).
- Receiving TV obtains the same camera/server permissions as the copied credentials. Revoking the transfer requires
  revoking/rotating those credentials at their source; there is no account-level device manager in this preview.
- Host tests do not establish Android Keystore, camera permission/scanner, remote focus or actual TV playback proof.
  Keep release prerelease until phone-to-TV acceptance and stream behavior are checked on real devices.

## Recovery

Mobile data is unchanged. TV can retain its previous configuration or pair again. The preceding APK
remains available; Android normally requires uninstalling before a versionCode downgrade.

## 0.3.1 Welcome Screen

- Centered brand, heading, primary phone pairing action and secondary manual connection entry.
- No reload or camera controls before configuration. Phone pairing receives initial remote focus.
- Manual entry opens the existing Frigate, ONVIF and HA setup activities; on return, their saved
  configuration is copied to the atomic TV configuration without touching another device.
- On the connected Smart TV Pro, the empty-install instrumentation test passed. Screenshot inspection
  confirmed the selected primary action; DPAD down/center opened all three manual source choices and
  Back returned to the welcome screen. No credentials were entered and no camera was opened.
- Authentication and actual video playback remain outside this UI-only device validation.
