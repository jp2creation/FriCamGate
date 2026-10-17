# Frigate connections

## Contract

- Open Frigate settings in a dedicated activity, matching the existing ONVIF and Home Assistant entry points. Leave those activities and their stores unchanged.
- List, add, rename, reconnect and remove Frigate servers locally. Authenticate before saving; never retain passwords.
- Migrate the existing connection once, retaining its camera/event keys. Store additional credentials with Android Keystore and AtomicFile, as existing providers do.
- Combine dashboards and histories. Qualify additional servers' camera, stream and event identifiers, and route every request to its owning server, including playback, audio and background alerts.
- A failed server must not log out working servers. Removed servers cannot publish late notifications.
- Keep chat explicitly scoped to a selected server; do not suggest cross-server AI search.

## Implementation and Checks

1. FrigateServerStore: encrypted atomic profiles, bounded URL/identity validation, legacy fallback and migration. Tests cover migration, duplicates and removal isolation.
2. MultiFrigateApi: reuse FrigateNativeApi transports, qualify/decode identifiers, combine reads and select credentials by origin and path. Mock servers test identical camera/event names, partial failures, filters, media URLs and credential isolation.
3. FrigateServersActivity and MainActivity: shared ConnectionFormUi, async login/error/cancel handling, server list and local deletion confirmation. Preserve ONVIF/HA data and protect with app lock.
4. CameraNotifications: independent server cursors and deduplication; qualified notification intents and camera policy keys.
5. Run unit tests, build, lint and isolated instrumentation. Verify actual screens on an emulator; real second-server connectivity remains unverified without a second installation.

## Migration Safety

Write and read back the encrypted snapshot before replacing the legacy session mirror. Never clear other providers. Do not contact siren/alarm endpoints. Do not publish until requested; retaining the prior APK is the rollback path.
