# Frigate acceptance: 0.2.59

## Scope

Prioritize Frigate playback, audio transitions and Android notifications. No
camera-IP connector, additional NVR, server configuration or credential migration.

## Corrections

- Apply notification category/camera/hour filters before the 20-alert batch cap.
  A disabled vehicle burst must not crowd out a selected person alert.
- Return to live with one decelerating animation. Programmatic scroll positions
  must not request intermediate recordings. Touch cancels the animation; detaching
  the timeline cancels its animator and queued playback.
- Stop live audio immediately on entering recorded playback. Recording fallback
  must preserve playWhenReady rather than override a manual pause.

## Evidence

- 52 JVM unit tests, APK/test APK compilation and lint passed.
- 15 Android regression checks passed on the unlocked Pixel emulator, including
  the notification burst regression (failed before the filtering correction).
- Opt-in real-recording Android check: first video frame rendered, playback
  position advanced, manual pause held position. Used the real Frigate backend
  through a temporary local SSH tunnel, not the public authenticated route.
- Frigate remained healthy; four cameras had recent recordings. Read-only probes
  only. No new public port or server configuration change.

The opt-in test is realRecordingRendersAdvancesAndHonorsPause, with instrumentation
arguments recordingServer, recordingCamera and recordingFocus. Do not put real
credentials in arguments or logs. Run it only against an authorized test path.

## Phone acceptance still required

1. Scroll to an older recording: video autoplays and time advances. Zoom and
   switch dates; compare the selected time, event timestamp and recording image.
2. Return to live, interrupt the return with a scroll, then switch camera/page.
   No delayed return or sound from a previous view should remain.
3. Enable sound on a supported camera, switch to history, pause/resume and enter
   fullscreen. Confirm actual audibility and absence of overlapping streams.
4. Test Android notification, then generate a real matching event with the phone
   locked. Confirm sound/vibration and filtered categories. OS permission,
   notification channel and Do Not Disturb settings retain final control.

Notification polling is not guaranteed instant push: periodic background work is
scheduled at a minimum of 15 minutes; optional foreground monitoring is faster
but time-limited. Force-stop and battery restrictions can prevent delivery.
No claim of final phone acceptance is made until these checks are confirmed.
