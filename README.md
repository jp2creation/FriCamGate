# FriCamGate

FriCamGate is an Android phone and tablet client for Frigate.

Each user connects the app to their own Frigate server URL and signs in with their own account. The V1 design is a direct Frigate client with no central FriCamGate backend.

## Current Status

This repository contains the V1 debug Android APK project built with React, Vite, TypeScript, and Capacitor Android.

The debug APK can be built locally with:

```bash
npm ci
npm run android:debug
```

## Brand

The approved logo master is stored at:

- `assets/brand/fricamgate-logo.png`

## Docs

- V1 design: `docs/superpowers/specs/2026-10-12-fricamgate-v1-design.md`
- Implementation plan: `docs/superpowers/plans/2026-10-12-fricamgate-v1-implementation.md`

## Security Note

Do not commit Frigate credentials, JWTs, cookies, `.env` files, signing keys, or generated release keystores. Users enter their own Frigate server URL and credentials inside the app.

HTTPS is the default. HTTP is available only when the user explicitly enables the insecure HTTP option in the setup screen, because usernames and passwords may travel in clear text over HTTP.
