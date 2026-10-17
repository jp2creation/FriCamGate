# Approved EN1 + A2 TV presentation

TV only. Adopt the approved EN1 horizontal header across the shared shell:
logo, Home, Events, Notifications, Settings. No Cameras entry. Preserve the
optional chat/bell settings and all phone/tablet layouts.

1. MainActivity.setShell/bottomNav: one compact 56dp top header, no side rail or bottom
   navigation on TV; A2 home remains four whole camera tiles and page dots.
2. TvHistoryView: events become a five-thumbnail horizontal filmstrip, with
   left/right paging. Inbox remains five readable rows, now in a left pane.
   Preserve selected IDs, day pages, filters and unread actions.
3. TvEventPreview: shared native video preview; events get large video with a
   compact right metadata panel above the filmstrip; inbox gets a contextual
   video preview on the right. Focus updates still/metadata without opening a
   stream. OK starts the selected recording, controls allow seek/pause/sound,
   fullscreen uses the existing event viewer. Back stops playback and restores
   the selected item. Async images must match the still-selected event.
4. No new media URLs, authentication, storage migration, live behavior or
   camera actuation. TextureView and existing MP4 path retained on TV.
5. Test unit layout policy first; build/unit/lint; physical TV fixture captures
   for home/events/inbox with DPAD, page restoration, horizontal menu and read
   state. Real video verification on Devant only. No emulator or real Ring QA.
6. Publish a signed debug prerelease, download/hash-check/install the public
   artifact without clearing data. Keep 0.3.8 for recovery. Confirm encrypted
   configuration unchanged before/after. Do not claim Play Store publication.
