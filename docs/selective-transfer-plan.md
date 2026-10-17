# Selective device transfer

Approved outcome: QR or complete invitation code, all/appearance/cameras/custom
selection, category and individual camera checkboxes, sender summary, receiver
confirmation with merge by default and explicit replacement. Shared themed UI.

1. TvConfiguration: persist a per-Frigate-server camera selection in schema 3;
   preserve versions 1/2 and scope in all store updates. MultiFrigateApi filters
   dashboard/history results. Server credentials retain their original rights.
2. ConfigurationTransfer: bounded versioned envelope, allowlisted appearance
   settings, camera/category selection and metadata; deterministic merge without
   dropping unselected receiver cameras. Unknown fields/settings rejected.
3. TvTransferActivity: source inventory, presets, category/individual checkboxes,
   summary, explicit credential consent. QR and full-code input. Receiver entry
   also available on phone/tablet; no discovery service or cloud.
4. TvActivity: decode envelope or legacy payload, merge/replace confirmation,
   rollback settings/config on failure, never execute device controls.
5. Unit tests first for selection, merges, collisions, schema rejection and
   appearance allowlist; native UI and loopback TLS with fictitious data.
6. Build/lint, physical TV DPAD, unchanged encrypted user config, immutable
   prerelease 0.3.13/code136, public APK hash/signature and installation checks.

No automatic synchronization. The existing six-digit number is a verification
code, not a standalone discovery/authentication mechanism. Manual entry uses
the complete pinned invitation; no reduction of TLS authentication strength.
Receiver compatibility is explicit: old versions reject the new envelope.
