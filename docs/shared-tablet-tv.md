# Shared tablet/TV interface

## Approved contract

MainActivity remains the single feature UI for phones, tablets and configured TVs.
TV uses the existing horizontal tablet shell, menus, settings, event playback and
camera controls. TvActivity retains first-time pairing; a configured TV enters
MainActivity. The phone/tablet path is unchanged.

TV presentation forces the tablet landscape policy even when a TV reports a
smallest width below 600dp. Navigation receives visible remote focus, larger
text, safe margins and keyboard scrolling. Existing camera reorder menu supplies
up/down actions instead of requiring a drag. Shared sheets receive remote focus.
No hardware command is mapped to a direction or focus event.

## Storage boundary

On a television only, provider stores and camera arrangement read/write their
portion of tv-config.enc through an atomic locked update. Other providers,
hidden cameras and order are preserved. No migration copies secrets into phone
preference files. Non-TV contexts retain their original stores. Dedicated test
storage names remain isolated. Imported TV configuration remains authoritative.

Existing TV appearance is adopted once into local shared-UI appearance preferences.
No data is transferred back to a phone. Pairing remains an explicit one-time copy.

## Verification

Test phone/tablet/TV policy, provider isolation and field-preserving updates.
On the physical TV, enter shared settings without opening live cameras, exercise
remote focus and shared sheets, and render the existing tablet shell using fixture
camera images. Preserve the real TV configuration. Never run the older destructive
fixture setup tests against the configured TV. No emulator or camera actuation.
Preserve main-stream selection for expanded Frigate TV playback.

The user has authorized publication of the shared-TV update.

## Observed checks

Debug application, instrumentation APK, unit tests and lint build successfully.
Three targeted tests pass on the physical Smart TV: shared landscape settings,
real DPAD-right focus movement, isolated provider updates and launcher artwork.
The settings screenshot was inspected at 1920x1080. The real encrypted server
file was byte-identical before and after the settings test.

## TV cleanup and live-quality correction

Removed the duplicate TV dashboard, administration, UI tokens, workspace helpers,
their obsolete tests and dedicated workspace strings. TvActivity now contains only
first-time entry and explicit, consent-based pairing; configured screens use MainActivity.
TV remote focus is a subtle cell background highlight, without a colored outline.
Existing configuration files remain in place.

Read-only Devant diagnosis found a 640x360 JPEG preview and an HLS address returning
HTTP 200 text/html instead of a playlist. The now-unused HLS discovery implementation
was removed. TV expanded live uses go2rtc MSE original fragmented MP4 into Media3,
with the existing per-server authenticated/local-route transport. TV thumbnails
request 960px from the main stream; phone preview behavior stays unchanged.
The live data source has an 8MiB backlog ceiling, read timeout and release cancellation.
The TV TextureView renderer produced an inspected 3840x2160 HEVC image; its pixels
were copied at 1280x720 for inspection rather than relying on the TV overlay capture.
No camera settings, siren, lighting or PTZ commands were sent.

Appearance includes per-device visibility switches for recent events and the
camera title/layout/filter toolbar. Both default to hidden on TV and visible on
phones/tablets. The events switch hides the strip or tablet sidebar without removing Events access.
Appearance reset restores the default visibility.

Final physical-TV acceptance: 7 targeted tests pass, including the actual
MainActivity expanded-camera path at 3840x2160, advancing playback and non-flat
decoded pixels, recent-events toggle persistence, DPAD focus, encrypted-store
preservation, data-source partial reads/backpressure/cancellation and launcher
centering. TV home tiles have one whole-tile click target and no embedded actions.
DPAD Center opens immersive playback with audio enabled; supported hardware
controls are available through the fullscreen settings sheet, never activated by focus.
The live acceptance check covers the tile click, immersive state, initial volume
and options entry without actuating camera hardware.
Settings and decoded video captures were inspected. Unit tests and
lint pass. Installed locally on the TV; no public release was created.

## Viewport grid and revised video chrome

TV home uses TvCameraGrid within the existing shared shell. Two-column mode fits
two complete rows into the available viewport; one-column mode fits one video.
DPAD navigation moves between camera tiles and switches complete four-camera pages.
The four-camera fit and eight-camera scrolling are tested on the physical TV
using the production tiles and shell with fixture photographs, not live sources.
TV tile measure constraints respect the available height and use FIT_CENTER.

Camera names use compact corner labels, without a full-width caption band.
Video focus has a fine neutral light/dark halo according to the theme;
settings cells retain their background focus indication.

The TV Frigate player now requests Media3-managed audio focus with MEDIA/MOVIE
attributes and routes volume keys to the media stream. The read-only Devant
probe checks an AAC track is selected, decoded output buffers advance and player
volume remains 1. System media volume/mute are observed, never changed. These
checks do not substitute for the user's confirmation of sound from the speakers.

## 0.3.6 release gate

The final shared-TV revision uses four-camera pages with right-hand page dots,
black letterboxing, an inset layered light/dark focus halo and audio exclusively
on the focused tile. Hidden Frigate pages release their preview sockets.
The TV rail no longer contains the redundant Cameras entry. Events use six-item
pages, the shared history filters and a landscape video/details playback layout.
Phone and tablet layouts keep their existing presentation.

All 10 selected physical-TV tests pass. The Devant-only test verifies selected
home-tile AAC decoding, audio release on focus exit, advancing 3840x2160 playback,
decoded fullscreen audio and unchanged encrypted TV configuration. Event browser,
event detail and camera-page captures use fixture photos. Source build, unit
tests and lint pass. No emulator or camera hardware command was used.
