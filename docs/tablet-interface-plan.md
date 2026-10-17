# Tablet interface parity

The approved Home mockup is the reference: landscape navigation rail, two camera
columns and activity sidebar; portrait activity strip, two columns and bottom
navigation. Extend that same workspace to the remaining existing routes, without
inventing new features or changing phone/TV defaults or saved connections.

1. Extend TabletLayoutPolicy with shared landscape workspace and history column
   decisions. Unit tests must cover portrait, landscape, narrow windows, phones
   and TV isolation before implementation.
2. In MainActivity, keep shared header clear of tablet system bars; use an accent
   selected navigation tile. Place camera timeline and event details beside their
   player in landscape, with stacked content in portrait. Keep controls intact.
3. Give landscape settings a persistent section selector and full-width detail
   area; preserve existing destinations, callbacks and back handling. Render
   notification rows in two columns only when sufficient width remains.
4. Add scoped emulator instrumentation with in-memory fictional camera/event
   fixtures. Never clear provider stores. Restore temporary appearance changes.
   Assert positions, navigation and system insets, and capture rendered screens
   in both orientations. Check phone layout isolation in unit tests.
5. Run unit tests, lint, APK/test build and tablet instrumentation. Inspect actual
   screenshots, fix overflow, then version and publish the verified APK to GitHub.
   Verify the public asset hash and CI result. Stop the owned emulator afterwards.

No persistence migration is needed. Reverting this isolated UI commit and using
the preceding APK restores the previous presentation without changing camera data.
