# Multi-NVR migration

Camnory remains one Android application. No new NVR is advertised before its
complete connection/live/playback path is tested on a real server.

## First slice

- Extract FrigateNativeApi from MainActivity without changing its HTTP paths,
  authentication, media transport, or event parsing.
- Introduce NvrConnector for login, dashboard discovery, and version queries.
- Share existing native camera/event models, retaining nullable capability flags.
- Use the discovery contract in the current login/dashboard workflow.
- Test Frigate HTTP contracts with MockWebServer, then run existing Android
  playback/notification regression tests. Preserve unrelated working-tree changes.

## Following slices

### Second slice: media boundaries

The recording players consume NvrRecordedMedia, and live image playback consumes
NvrLiveFrames. Image loading and event history have separate contracts as well.
Frigate implements these contracts without changing routes or authentication.
Live audio, camera controls, chat and notification polling are not yet migrated.
The live frame contract is optional for future providers: it is not a requirement
that every NVR use Frigate's go2rtc transport. No provider selector, profile
migration or unsupported-server compatibility claim is introduced by this slice.

Acceptance: Frigate URL/authentication contract tests, Android playback regression
tests, compilation and lint. A real second server is still required to certify a
second connector. Rollback is a code revert; sessions and preferences are unchanged.

Second-slice checks: 52 unit tests, debug/test APK builds and lint passed. The
Android regression suite could not start its app setup after the emulator's cold
boot: credential-encrypted preferences remained locked. Runtime acceptance for
this slice is pending an emulator unlock; do not treat the unit checks as device
playback proof. Player HTTP authentication still uses Frigate's bearer scheme
and must become provider-specific before connecting a different NVR.

- Split media playback, camera controls, chat, and alert polling into capability-
  aware provider contracts. They are still Frigate-specific after the first slice.
- Implement Blue Iris authentication and camera discovery from its official JSON
  API, then live playback. Validate against an accessible real Blue Iris server.
- Only then expose a server-type selector; integrate recordings, timeline and
  events incrementally, hiding unsupported controls rather than simulating them.
- Add multiple server profiles with isolated sessions, histories and alert cursors
  after one complete non-Frigate connector has passed acceptance tests.

Existing Frigate sessions and preferences are unchanged. No server configuration
or Android package ID changes. Rollback of this structural slice is a code revert;
there is no data migration. True cross-NVR compatibility is not delivered by the
first extraction alone.

First-slice verification: unit tests (including three Frigate HTTP contract tests),
debug/test APK builds and lint passed. Nine Android regression tests passed on
emulator-5562 (runtime stability and Android notification delivery suites).
No Blue Iris server has been connected or certified by these checks.
