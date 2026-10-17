# TV follow-up included in 0.3.7

## Recorded playback

The real TV probe reproduced two failures on a completed Devant event:
HLS segment requests resolved against the local response URL were rejected by
the canonical-origin media guard; after correcting that route, the MStar HEVC
decoder failed on HLS playback. The same event plays as progressive MP4 at
3840x2160. TV event detail therefore uses the existing event clip endpoint and
the TV TextureView renderer. Phone/tablet event source selection is unchanged.

Configured local media aliases are mapped back to their canonical server before
credential attachment and network routing. Unknown hosts, sibling prefixes,
URL credentials and ambiguous local aliases remain rejected. Four unit cases
exercise these boundaries.

## Presentation

- Header chat and alert buttons have independent Appearance switches. Both
  default to hidden on TV; existing explicit choices are preserved. Notification
  delivery already defaults to disabled and is not automatically enabled here.
- Successful startup completes its loading line to 100% before opening content,
  including reduced-motion mode. Failed requests do not receive a success finish.
- TV related-camera thumbnails have one whole-tile action and no redundant
  fullscreen icon. The main video fullscreen action remains available.
- The earlier pending halo and uniform-preview changes remain included.

## Acceptance

Build, unit tests and lint pass. Ten selected physical-TV tests pass, including
real Devant recorded playback, pause/play resume, loading completion in both
motion modes, header defaults and persistence, grid navigation and removal of
the related-thumbnail action. Fixture screenshots of the header/event layout
were inspected. Encrypted provider settings remain unchanged. No emulator or
camera hardware command was used. This follow-up is installed for validation,
included in the user-authorized 0.3.7 release.
