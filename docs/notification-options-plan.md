# Android notification improvements

Scope: native Android only; no Frigate/server changes or cloud push credentials.

1. Add a pure NotificationPolicy with category/camera filters, local active hours,
   quiet hours, and per-camera/category cooldown. Unit-test overnight intervals,
   disabled categories, recognized people, hidden cameras, and cooldown boundaries.
2. CameraNotifications: separate audible/vibration/silent Android channels;
   bounded cursor overlap for late events, persistent delivery diagnostics,
   confirmation-aware deduplication, authenticated thumbnail, and existing event deep link.
3. MainActivity: native switches, multi-choice camera picker, time pickers,
   cooldown choices, channel settings, actual diagnostic state, and test delivery feedback.
4. Optional user-started dataSync foreground monitoring with persistent Stop action,
   20-second checks, explicit Android timeout handling, and WorkManager fallback.
   Do not promise instant push or operation after force-stop.
5. Run unit tests, assembleDebug, lintDebug and Android notification tests;
   check system notification receipt on emulator separately from server evidence.
6. Publish versioned APK and scoped Git commit. Preserve existing preferences;
   settings default to all categories/cameras, all hours, no cooldown, sound/vibration.

Rollback: disable enhanced monitoring in settings; previous APK can be rebuilt from
the prior commit. New notification settings do not alter the Frigate server.

## Verification

- Unit tests, debug APK build, Android test APK build, and lint: passed.
- Five Android instrumentation tests passed on emulator-5562: system receipt with
  sound/vibration channel, filters/quiet-hours/cooldown/deduplication, persisted UI
  switches, authenticated picture, and foreground monitor lifecycle/background state.
- Rendered settings checked from an actual emulator screenshot.
- Physical phone sound/vibration and manufacturer-specific battery behavior remain
  to be checked on the user's device. No FCM or server-side push was configured.
- Android channels remain user-controlled:
  https://developer.android.com/develop/ui/compose/notifications/channels
- Android dataSync foreground-service limits:
  https://developer.android.com/develop/background-work/services/fgs/timeout
