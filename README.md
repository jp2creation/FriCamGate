# Camnory

Camnory is an Android phone and tablet client for Frigate.

Each user connects the app to their own Frigate server URL and signs in with their own account. The V1 design is a direct Frigate client with no central Camnory backend.

## Current Status

This repository now contains the Android-native Kotlin direction for Camnory.

Android application ID: `com.nvr.camnory`. From 0.2.52, this installs alongside
the former `fr.degert.fricamgate` app. Android does not automatically transfer
private preferences, chat history, or notification permissions between these IDs.
Reconnect to the Frigate server and grant notifications in Camnory; keep the old
app until the new connection is verified. The GitHub repository URL is unchanged
so existing download links remain valid.

The current Kotlin build replaces the Capacitor WebView shell with a native Android activity, OkHttp API calls, and Media3/ExoPlayer playback. The earlier React/Capacitor prototype remains in the tree as reference material while the native app reaches feature parity.

The debug APK can be built locally with:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

The generated APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Brand

The approved logo master is stored at:

- `assets/brand/fricamgate-logo.png`

## Docs

- V1 design: `docs/superpowers/specs/2026-10-12-fricamgate-v1-design.md`
- Implementation plan: `docs/superpowers/plans/2026-10-12-fricamgate-v1-implementation.md`

## Security Note

Do not commit Frigate credentials, JWTs, cookies, `.env` files, signing keys, or generated release keystores. Users enter their own Frigate server URL and credentials inside the app.

HTTPS is the default. HTTP is available only when the user explicitly enables the insecure HTTP option in the setup screen, because usernames and passwords may travel in clear text over HTTP.
