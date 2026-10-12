# FriCamGate V1 Design

Status: proposed
Date: 2026-10-12
Owner: Jean-Philippe
Reader: future implementer of the Android APK

## Outcome

FriCamGate is an Android phone and tablet app for Frigate users. Each user connects the app to their own Frigate server URL and signs in with their own Frigate account. The app provides a dark, camera-first mobile interface inspired by the supplied camera.ui screenshots, without copying camera.ui branding or assets.

The first real test server is `https://cam.degert.fr`. No production secret, password, JWT, or admin credential is stored in the repository or bundled in the APK.

The approved brand logo is stored at `assets/brand/fricamgate-logo.png`. It is a square PNG master asset and should be used for the app header, launch branding, and Android icon derivation.

## Product Scope

V1 is a direct Frigate client. There is no central FriCamGate backend, no hosted user database, and no account service controlled by us. This keeps the app reusable for other people: every user brings their own Frigate server.

V1 supports:

- Add one Frigate server profile.
- Log in to Frigate with username and password.
- Store the active session or bearer token in Android secure storage.
- Validate the session on app launch.
- Display all visible cameras.
- Display recent events with thumbnails.
- Open a camera detail screen with live image/video and a simple event timeline.
- Work on Android phones and tablets with responsive layouts.
- Package a debug APK first, then a release APK once the flow is verified.

V1 does not support:

- A hosted FriCamGate cloud service.
- Sharing one Frigate server between multiple FriCamGate-managed accounts.
- Writing Frigate configuration from the app.
- Creating or deleting Frigate users.
- Two-way audio.
- Push notifications.
- Public Play Store release.
- iOS.

## Architecture Decision

Build FriCamGate as a Capacitor + React + TypeScript app packaged for Android.

Reasons:

- The UI can be built quickly with a polished camera-console feel.
- Capacitor gives an installable APK while keeping the screen layer in React.
- The same UI can be tested in a desktop browser before Android packaging.
- Native Android plugins can handle secure storage and HTTP edge cases.

The app has four internal layers:

- `frigateApi`: owns URL normalization, login, token refresh, authenticated requests, and typed responses.
- `sessionStore`: owns secure token/session storage and server profile metadata.
- `cameraData`: loads cameras, events, snapshots, and live-stream metadata from Frigate.
- `ui`: renders phone/tablet layouts, cards, timelines, error states, and settings.

## Frigate Integration

FriCamGate targets authenticated Frigate instances exposed over HTTPS. Frigate supports user/password login through `POST /api/login`, returns a JWT session cookie, and can also use the token as an Authorization bearer token. Frigate exposes `/api/auth` to validate or refresh authentication state. Source: https://docs.frigate.video/configuration/authentication/ and https://docs.frigate.video/integrations/api/auth-auth-get/

The first connection flow is:

1. User enters server URL, username, and password.
2. App normalizes the URL: trims whitespace, requires `https://` by default, removes trailing slash.
3. App calls `POST /api/login`.
4. App validates the result with `/api/auth` and `/api/config`.
5. App stores only the resulting session material, not the plain password.
6. App discards the password from memory after login.

The app should support Frigate roles instead of assuming admin access. Frigate supports `admin`, `viewer`, and custom roles when accessed through the authenticated port or a reverse proxy that preserves role information. FriCamGate V1 is a viewer-first app: if the authenticated user cannot access a camera or endpoint, the UI shows a scoped permission message instead of retrying as admin. Source: https://docs.frigate.video/configuration/authentication/

Camera discovery uses `GET /api/config`, which is available to authenticated users. Events use `GET /api/events` or `GET /api/events/search`; event snapshots use `/api/events/:event_id/snapshot.jpg`; camera latest frames use `/api/:camera_name/latest.jpg` or `.webp`. Sources: https://docs.frigate.video/integrations/api/config-config-get/, https://docs.frigate.video/integrations/api/events-events-get/, and https://docs.frigate.video/integrations/api/latest-frame-camera-name-latest-extension-get/

## Live Video Strategy

V1 prioritizes dependable display before lowest latency:

1. Show latest camera frame immediately.
2. Try Frigate-compatible live playback where the server supports it.
3. Fall back to periodic latest-frame refresh if live playback fails.

Frigate live view can use smart streaming and go2rtc-backed technologies such as MSE and WebRTC. WebRTC may require additional network setup, including port `8555` TCP/UDP for external access. For this reason, V1 must not promise universal zero-latency streaming. It should detect failure, keep the camera usable with refreshed frames, and surface a concise diagnostic. Sources: https://docs.frigate.video/usage/live/ and https://docs.frigate.video/configuration/live/

Implementation note: because a Capacitor WebView can hit cross-origin and credential behavior that differs from normal browser use, API requests should be routed through Capacitor native HTTP when needed. Media playback should be validated on a real Android device against `https://cam.degert.fr` before choosing the final live provider. Source: https://github.com/ionic-team/capacitor/blob/main/core/http.md

## Security

FriCamGate handles private home-camera access, so the default security posture is conservative.

Rules:

- Do not hardcode server credentials.
- Do not commit `.env` files, tokens, cookies, generated APK signing keys, or Frigate secrets.
- Do not log passwords, JWTs, cookies, Authorization headers, or full request headers.
- Require HTTPS for normal setup.
- Allow HTTP only behind an explicit advanced setting for local lab use, with warning text.
- Store session material in Android secure storage.
- Offer "Forget server" and "Log out" actions that clear stored session material.
- Treat camera names, event labels, snapshots, and clips as private data.

For secure storage, the implementation should use an Android-backed secure storage plugin or a small native wrapper around Android Keystore/EncryptedSharedPreferences. If `@aparajita/capacitor-secure-storage` is used, verify its current Android behavior during implementation; its documentation states that Android values are encrypted with a key generated by Android KeyStore and stored in app-specific SharedPreferences. Source: https://github.com/aparajita/capacitor-secure-storage

## Visual Design

The supplied screenshots establish the V1 visual language:

- black background
- large rounded camera cards
- camera name in a dark translucent pill
- live age badge in the top-right corner
- quick action buttons clustered on the right/bottom of cards
- horizontal recent-event strip
- bottom navigation with five main sections
- detail screen with a top live panel and a vertical event timeline

FriCamGate should not use the camera.ui logo, name, icon, or exact asset treatment. It should feel like a sibling product category: dark, direct, calm, and fast.

Brand asset:

- Use `assets/brand/fricamgate-logo.png` as the FriCamGate logo master.
- Keep the logo on dark backgrounds with enough padding so the shield/camera shape remains legible.
- Generate Android launcher icons from this master during implementation; do not hand-edit the original.

Initial sections:

- Home: recent events strip plus primary camera cards.
- Cameras: full list of cameras.
- Grid: tablet-friendly dense grid and phone two-column option.
- Events: searchable/filterable event feed.
- Settings: server profile, session state, app theme, diagnostics, logout.

Phone layout:

- Home uses one-column cards.
- The latest event strip scrolls horizontally.
- Camera detail opens full-screen, with tabs for Live and Events.

Tablet layout:

- Home uses two-column cards in portrait and three-column cards in landscape when space allows.
- Camera detail can show live view and event timeline side by side.

## Data Model

Local app state:

- `ServerProfile`: id, displayName, baseUrl, createdAt, lastConnectedAt.
- `StoredSession`: profileId, token or cookie payload, expiresAt when known, role when known.
- `CameraSummary`: name, friendlyName, enabled, snapshotUrl, liveCapability, lastSeen.
- `EventSummary`: id, camera, label, subLabel, startTime, endTime, hasClip, hasSnapshot, thumbnail.
- `ConnectionHealth`: serverReachable, authenticated, role, apiVersion when available, lastError.

Persistent storage:

- Profile metadata may be stored in ordinary app preferences.
- Session material must be stored in secure storage.
- Cached thumbnails may be stored only as an optional performance cache and must be clearable.

## Error Handling

Every network state gets a user-facing result:

- Bad URL: explain expected format and keep the user on setup.
- TLS/certificate error: explain that HTTPS is required and avoid bypassing silently.
- Login rejected: show "identifiants refuses" without revealing server details.
- Expired session: return to login for that server.
- Permission denied: show camera or endpoint is not available for this Frigate role.
- Live stream unavailable: keep snapshot refresh active and show "live indisponible".
- Server offline: keep cached camera names/events if present and show last successful connection time.

Errors should be concise in UI and detailed only in a diagnostics panel. Diagnostics must redact tokens and cookies.

## Testing And Verification

The V1 build is not complete until these checks pass:

- Browser UI renders home, setup, events, settings, and camera detail at phone and tablet widths.
- Android debug APK installs on a real or emulator Android target.
- Login succeeds against `https://cam.degert.fr` using manually entered credentials.
- Credentials are not present in source, build files, command history snippets, logs, or APK assets.
- `/api/auth` and `/api/config` are called successfully after login.
- At least one camera card displays a current image.
- Recent events load with thumbnails.
- Live fallback works when streaming fails.
- Logout clears session and prevents subsequent API calls until login.
- Tablet layout does not overlap text/buttons at common breakpoints.

## Implementation Milestones

1. Bootstrap project: Vite React TypeScript, Capacitor Android, lint/typecheck/test scripts.
2. Build static UI shell: responsive camera-console layout with mocked data.
3. Add Frigate API client: URL normalization, login, auth check, config, events, snapshots.
4. Add secure storage and session lifecycle.
5. Connect real data from `https://cam.degert.fr`.
6. Add live/snapshot playback fallback.
7. Package debug APK and test on Android.
8. Generate app icon variants from the approved logo, then polish release name and signing path.

## Open Decisions Before Implementation Plan

These are the only remaining product choices:

1. Default server field: prefill `https://cam.degert.fr` for your private test build, or leave blank for the reusable build.
2. V1 live target: start with snapshot refresh plus event playback, or attempt go2rtc/WebRTC in the first APK build.

Recommendation:

- Prefill `https://cam.degert.fr` only in the private debug build.
- Build snapshot refresh and event playback first, then add go2rtc/WebRTC once login, camera discovery, and events are verified.
