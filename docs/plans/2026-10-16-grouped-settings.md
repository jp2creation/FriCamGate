# Grouped settings, approved option 1

Outcome: wider mobile settings blocks, three recognizable sections and eight
entry points. Installation: Connections, Cameras. Preferences: Notifications,
Appearance, Language. Application: Security, Updates, About. Connections owns
Frigate/ONVIF/Home Assistant setup; existing credentials and supported features
are preserved. No alarms/sirens, new notification providers or publication.

Implementation and proof:
1. New isolated SettingsNavigationInstrumentationTest, without modifying the
   pre-existing dirty SettingsFeaturesTest. Observe absent grouped navigation
   before implementation. Use localhost mock Frigate and backed-up preferences.
2. MainActivity groups existing destinations, adds connections/security/updates
   and licenses pages, logical back parents and saved-page restoration, including
   standalone sources. External camera managers return to their launching page.
3. Widen settings containers to 12dp phone gutters (bounded width on tablets),
   add thin icons, soften groups to 8dp radius, allow rows to grow for translations.
   Keep existing appearance scroll preservation and in-place update-card refresh.
4. CameraArrangementView exposes existing source-specific camera settings from
   the name area, independently of visibility and reorder controls.
5. Add six-locale labels. Test groups/order, routes/back/recreation, connections,
   settings width, updates callbacks and appearance persistence. Capture dark and
   light at phone widths, test enlarged text. Run unit tests, lint and Android
   navigation/HA suites on isolated Camnory_HA_QA emulator, not user's emulator.

Files: MainActivity.kt, CameraArrangementView.kt, settings-navigation drawables,
localized settings_navigation_strings.xml, focused instrumentation test and
existing HA navigation tests only where their route changed intentionally.

Rollback: revert scoped UI/routing changes. Existing connection files and
preference keys are unchanged. Builds/emulator proof do not imply publication or
physical-phone validation. New optional pages add no background device control.
