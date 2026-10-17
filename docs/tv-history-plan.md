# TV history and remote focus

Approved scope: TV only. Keep all media transports, encrypted connections and
phone/tablet layouts unchanged. Do not actuate camera accessories during QA.

1. TvRemoteSupport: fine accent edge with a soft falloff, no scale or layout
   change. Latest user revision: only the border is colored, never the name
   or its background. Keep the diffusion very light.
2. TvHistoryView: chronological day-bounded pages, six event tiles or five
   notification rows, compact metadata, shared remote focus and page controls.
   Reuse EventFilterSheet and existing read-state storage in MainActivity.
3. MainActivity: retain selected event ID across playback and async refresh;
   restore the correct browser (events or notifications). TV event playback
   gets a compact sidebar, dominant video, auto-hiding controls.
4. Tests: day paging unit cases, native TV fixture screenshots and DPAD checks,
   return-position checks, unread filters/actions using an isolated preference
   namespace, existing grid/connection/shared-shell regressions. Real Devant
   playback only. No emulator and no real Ring activation for fixture tests.
5. Release: unit/build/lint, physical TV checks, inspect screenshots; commit,
   publish signed debug prerelease, download and compare hash, install that APK
   without clearing data. Record evidence and any limitations.
