# Connection selector: approved proposal 2

User approved the compact source selector mockup, not publication. Replace the
three filled source buttons with one dropdown and show all provider forms in the
same initial page. Retain form drafts when switching providers. Small left-aligned
brand, modest heading, thin outlined fields, password eye controls, collapsible
advanced settings, one primary action. Preserve ONVIF search/saved cameras and
explicit consent for unencrypted local HA HTTP. No network or alarm scope change.

Implementation: shared `ConnectionFormUi` widgets and `HomeAssistantSetupView`
for both inline onboarding and the existing settings activity. MainActivity keeps
its existing Frigate login and ONVIF read-only connection paths. Cancel pending
HA/ONVIF work when changing providers, leaving, locking or backgrounding.

Checks: native selector/form/draft/secret/advanced tests first, HA selection and
storage regressions, full unit/build/lint checks. Real dark/light French renders
on the isolated QA emulator, not generated mockups, for user approval. Do not
commit, push, bump the release or publish an APK until user approves the result.
