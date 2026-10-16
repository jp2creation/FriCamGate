# Column cards and camera picker follow-up

Root cause: the 0.2.72 footer unification only matched grid mode. Frigate column
cards used overlays; HA/ONVIF still unconditionally added their grid footers.
The ONVIF badge also incorrectly reused a source-prefixed HA string.

The camera picker had three independent loops with different row structures,
ignored standalone visibility and mixed-source order, and omitted HA cameras
from its height calculation.

Changes:
- CameraColumnOverlay owns the common name chip, source icon, button dimensions
  and non-overlap layout. MainActivity and both source views use it in column
  mode. Grid mode and fullscreen retain their existing supported behaviors.
- HA keeps explicit live start; its settings affordance opens source details.
  ONVIF keeps live playback and its existing menu actions. No siren/control API
  was added. Camera frame aspect/cropping behavior is not changed.
- Picker reads the common ordered/filtered dashboard model. All sources get the
  same thumbnail slot and name row. HA snapshots and rendered ONVIF frames are
  reused in memory; missing images use a neutral placeholder. No new network
  request is made for standalone thumbnails.

Evidence:
- Column regression observed failing before the fix: HA had two card regions
  instead of one. Test now checks common height, overlays and non-overlap, and
  confirms that switching back to grid restores the compact footer.
- Picker test checks identical row geometry, icons before names, saved mixed
  order and hidden camera exclusion.
- Photo-fixture screenshots cover dark/light columns, dark grid and picker.
  These are real app renders but not evidence of real camera playback.

Release: 0.2.73 / code 96. Existing credentials, hidden preferences and camera
order are unchanged. Rollback can use 0.2.72 source/artifact; an Android version
downgrade requires device-side handling and is not automatically performed.
