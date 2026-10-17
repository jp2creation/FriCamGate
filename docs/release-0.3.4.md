# Camnory 0.3.4 TV preview

Frigate and Home Assistant settings, including TV settings, display the route
of the last completed successful request: Local, External, or Primary address
when no alternate route exists. The actual configured address used is shown.

Observations are process-local and provider-scoped. A health probe alone does
not establish success: the actual application request must complete. A failed
local read followed by a successful external fallback is reported as external.
An access error is not labeled connected. A changed Android network invalidates
the visible status until another request completes; a fresh process is unverified.
No credentials or URL query parameters are stored in this status registry.

Views refresh their labels every two seconds while attached and stop polling
on detach. Status polling does not request camera images, mutate devices or
change routing decisions. Existing Frigate settings health checks now use the
same route policy as playback instead of forcing the external address.

Release gates: app and instrumentation assembly, unit tests, lint, diff check,
signer verification, push to kotlin-native, GitHub prerelease and public APK hash
comparison. No emulator, camera actuation, or saved endpoint changes are required.
New labels have not been visually checked on a device; real endpoint switching
and playback quality remain user validation items. This is not a Play Store release.
