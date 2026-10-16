# Camera sources and arrangement

Approved scope: monochrome source icons before camera names; optional icons in
Appearance; one camera settings list for Frigate, ONVIF and Home Assistant with
persisted mixed-source order and non-destructive visibility. No device control,
alarm, siren, credentials, discovery or notification changes.

1. Add CameraArrangement pure ordering/key logic and regression tests. Use source,
   server and camera ID for identity, not display names. Preserve legacy Frigate
   hidden names; append new cameras without discarding temporarily absent IDs.
   Check: ./gradlew :app:testDebugUnitTest --tests '*CameraArrangementTest'.
2. Add shared source pictograms/view helper, sourced Frigate vector and attribution.
   Add appearance toggle; use adaptive monochrome (white in dark, dark in light)
   to keep contrast. Modify MainActivity, HomeAssistantCameraView, OnvifLiveCard.
3. Render home from a single ordered list of existing camera view factories.
   Replace camera visibility settings with shared mixed-source rows supporting
   local native drag/drop and accessible move commands. Persist drop only; no
   server calls. Keep scroll position and hidden cameras in arrangement.
4. Add localized labels, unit and device tests for ordering, hiding, keys, source
   icon placement and toggle persistence. Compile APK and run tests on isolated
   QA emulator, not user's configured emulator. Verify dark/light screenshots.

Rollback: revert these scoped source changes; existing encrypted connections and
legacy hidden-name preferences remain untouched. New preferences are additive.
Local builds/screenshots do not prove phone playback or publication.

## Release verification

- ONVIF follow-up: replaced the two-row footer with a single 48dp footer and
  overflow menu. All five existing commands remain available. Live badge is
  conditional on the first rendered frame, not on successful discovery alone.
- Regression test observed failing before the fix (three child regions instead
  of two), then passing at 90dp, 144dp and 296dp widths. Popup bottom action,
  fullscreen/settings callbacks and navigation dismissal checked on emulator.
- Unit tests, APK compilation and lint passed. Android ONVIF/Home Assistant
  suites completed with no failures (25 listed tests; optional hardware and
  capture cases gated by explicit instrumentation flags).
- Dark/light photo-fixture captures exercise the real card layouts. They are
  not evidence of live hardware playback. No production camera calls were used.
- Release 0.2.72 / versionCode 95. Published 0.2.71 APK SHA-256 and signer checked;
  new APK uses the same signer. Previous APK remains the rollback reference;
  downgrade on Android may require explicit device-side intervention.
- Pending web files and SettingsFeaturesTest edits predated this task and are
  excluded from the release commit.
