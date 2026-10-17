# TV settings workspace (0.3.16)

The approved Google TV-style proposal is implemented only for television devices.
Phone and tablet retain their existing settings navigation.

## Behaviour

- A persistent left column lists settings sections; the right pane reuses existing
  settings actions, stores, permission checks and confirmation sheets.
- Settings open on Display. Theme, accent, grid and home/header visibility appear
  first; additional appearance options remain below in the scrollable pane.
- OK opens a section; Right enters its controls; Left returns to its section.
  Accent swatches retain horizontal navigation. Back returns to Home.
- The selected section uses the current theme background and a small accent mark,
  not a heavy border or coloured label. Long labels wrap and both panes scroll.
- Connections, cameras/categories, sound, transfer, notifications, language,
  security, updates, about and licences remain accessible.
- No camera, category, credential or preference migration is performed.

## Verification

- Unit tests, debug APK assembly, instrumentation APK assembly and Android lint.
- A physical TV regression test covers sidebar/detail navigation, category-sheet
  opening without saving, accent navigation, and a native screenshot.
- A second read-only TV test covers administration sections and return navigation.
- The earlier 0.3.15 APK is the rollback artifact; installation uses an in-place
  update, never app-data clearing or application removal.

This release does not change video transport, recording, or camera controls.

## Release evidence

- Version 0.3.16, code 139; unit suite: 205 tests, no failures, one skipped.
- Both scoped instrumentation tests passed on the physical Google TV.
- Camera configuration and category file hashes were identical before and after
  installation and navigation tests. No user settings were changed by tests.
- APK SHA-256: `b7ffc9a2e80e2d54b1df3e288071597eccb9d265d9cd70bcf3a822a581f4e0c5`.
- Signing certificate matches the preceding public debug release.
- Phone/tablet rendering was not re-tested on a physical device in this release.
