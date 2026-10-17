# Camera display names and categories

Store application-only aliases and many-to-many camera/category membership by
the existing stable source/server/camera key. Never rename devices, entities or
stream IDs. Empty alias restores the original name. Category names are unique
ignoring case; deleting a category removes memberships and resets its filter.

Reuse the themed SelectionBottomSheet for category management and per-camera
editing. Preserve the existing camera settings gear, visibility and ordering.
Expose a category selector on Home whenever categories exist, even with the TV
camera toolbar hidden. One category at a time, or all cameras; combine with
source/visibility filters. Persist locally on each device through app restarts.
No new cross-device synchronisation contract is introduced by this change.

Verify model invariants, restart persistence, source identity isolation,
multi-membership, deletion, alias reset, filter intersections and native UI.
Use isolated preferences for QA and leave user cameras/settings unchanged.
The approved category flow is list -> plus -> name -> camera checkboxes.
Opening a category edits its name and memberships; deletion requires confirmation.
The camera pencil changes only its display name. TV inputs and actions have
explicit focus links. Verification uses real DPAD events, including waiting for
the TV keyboard to close before continuing. Publication authorised by the user.

## Verification

- Application, test APK, lint: successful.
- 197 unit cases, no failures/errors, one ignored.
- Native category tests cover metadata persistence and physical-TV DPAD
  creation, text entry, checkbox selection, saving and reopening.
- QA used isolated catalog preferences, a fictitious Frigate camera and no
  active NVR session. The category filter excluded real cameras before Home
  was rendered. No real source names, credentials or controls were changed.
- Encrypted TV configuration digest remained unchanged throughout QA.
- Release version: 0.3.12 / code 135. See release-0.3.12.md for delivery evidence.
