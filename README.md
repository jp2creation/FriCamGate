# FriCamGate

FriCamGate is an Android phone and tablet client for Frigate.

Each user connects the app to their own Frigate server URL and signs in with their own account. The V1 design is a direct Frigate client with no central FriCamGate backend.

## Current Status

This repository currently contains the approved V1 design and implementation plan. The APK implementation has not started yet.

## Brand

The approved logo master is stored at:

- `assets/brand/fricamgate-logo.png`

## Docs

- V1 design: `docs/superpowers/specs/2026-10-12-fricamgate-v1-design.md`
- Implementation plan: `docs/superpowers/plans/2026-10-12-fricamgate-v1-implementation.md`

## Security Note

Do not commit Frigate credentials, JWTs, cookies, `.env` files, signing keys, or generated release keystores. Users will enter their own Frigate server URL and credentials inside the app.
