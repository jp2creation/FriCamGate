# TV workspace v2 implementation contract

- Home: compact brand/header; selected camera takes the left two-thirds, at most
  three alternative previews on the right, with paged remote navigation.
- Selecting a preview changes the main camera; full-screen and camera settings
  are separate icon actions. Preserve source aspect ratio and source ownership.
- Settings: left categories Servers, Cameras, Picture and sound, Appearance,
  Diagnostics. Right pane scrolls independently and all actions are focusable.
- Servers: masked credentials, optional local route, read-only connection test,
  explicit save; update the encrypted TV configuration, not phone preferences.
- Cameras: visibility and order, remembered primary camera and per-camera side
  pane. Provider-supported controls only; unsupported capabilities disabled.
- Picture/sound: main or economy Frigate stream, preview resolution, muted start.
- Appearance: adjustable TV text size, dark/black surfaces and accent colors.
- Diagnostics: actual local/external status, explicit safe server tests, version.
- Imported configurations remain intact when adding one provider manually.
- TV launcher symbol: original supplied artwork, centered with circular-mask
  safe margins; mobile icons and rectangular TV wordmark stay unchanged.
- Verification: unit tests, native-view fixture rendering/focus checks on the
  physical TV without opening configured cameras, app/instrumentation builds,
  lint, signer and public hash. No emulator or hardware actuation.
- Release: next GitHub TV prerelease, same signed package. Keep limitations
  distinct from playback and physical-camera capability proof.
