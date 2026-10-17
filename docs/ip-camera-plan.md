# Direct IP cameras

Add an independent IP/Webcam source, without discovery or ONVIF commands.
Accept explicit rtsp/http/https URLs and bare host/IP (HTTP by default).
Require a name; username/password optional, kept in encrypted storage.
Reject local-file schemes, embedded credentials, control characters and fragments.
RTSP reuses Media3 TCP playback/authentication; HTTP supports HLS/MP4 through
Media3 and multipart MJPEG/JPEG through a bounded image stream reader.
No TLS bypass and no forwarding credentials to a different origin on redirects.

Expose the manager in onboarding and camera-source settings; add/edit/delete,
home previews, fullscreen, mute, retries and lifecycle cancellation. No fabricated
PTZ, siren, light, events or recordings. Reuse the TV grid and source ordering.
Extend encrypted phone-to-TV configuration additively; retain old configurations
and ensure all other provider writes preserve the new source.

Verify URL/parser/storage contracts, synthetic authenticated and unauthenticated
streams on the physical TV, failed credentials and pause/background cancellation.
Do not modify real camera settings or user connections for QA. Build/lint/test,
publish a new immutable APK and verify the downloaded artifact before installation.
