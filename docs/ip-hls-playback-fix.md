# Direct IP camera HLS playback (0.3.17)

## Root cause

The public beach streams were valid HLS, but IpCameraCard supplied the server's
MIME alias directly to Media3. Media3 expects its canonical HLS MIME constant;
the aliases selected a progressive file extractor instead and failed with
UnrecognizedInputFormatException before the first frame.

The earlier provisioning check used MediaItem.fromUri directly. That selected
HLS from the URL extension and did not exercise the actual application's MIME
handling. That check was insufficient to validate these cameras in Camnory.

## Fix

Normalize the two accepted HLS server MIME aliases to MimeTypes.APPLICATION_M3U8.
Keep video/mp4 canonical, and preserve URI inference for unknown MIME types.
No stream URL, credential, camera or category needs changing.

The real Home test also exposed BehindLiveWindowException from Zarautz. Follow
Media3's documented recovery: seek to the current default live position and
prepare the same player again. Other error codes keep the existing unavailable
state. Playback and mute intent are preserved.

Direct HTTP camera live streams target a five-second live offset using Media3's
media-source factory setting. This avoids a large inferred hold-back putting a
short rolling playlist near its expiring beginning. VOD behaviour is unchanged.

Reference: https://developer.android.com/media/media3/exoplayer/live-streaming

## Regression evidence

- Before the fix, all three saved public beach cameras failed inside IpCameraCard.
- A local MockWebServer test reproduced the failure with a URL without a media
  extension and an HLS MIME alias; the HLS variant was never requested.
- After the fix, that test requests the variant for all three MIME spellings,
  including a Content-Type header with charset parameters.
- Unit coverage checks canonical HLS, MP4, and unknown MIME handling.
- Live verification uses actual saved public cameras through IpCameraCard on the
  TV, rather than a separate bare player. Temporary live diagnostics are removed
  after use; permanent regression tests do not depend on public services.
- Live continuity is measured by decoded-frame growth, not by requiring an
  increasing currentPosition: that position is relative to a sliding HLS window.
- A temporary keep-screen-on flag prevents the TV's ambient screen from pausing
  the read-only Home smoke test; it does not change system sleep preferences.

## Artifact

- Version 0.3.17, code 140; 207 unit tests, no failures, one skipped.
- Debug APK assembly and Android lint passed.
- APK SHA-256: `53954de737b139885ced953df074f3ed8bd107f4fcaa2cfd672be5527d1dd4d8`.
- Final physical-TV Home test: all three public streams kept rendering together
  after one minute. Over the final ten seconds the rendered-frame counters grew
  by 257 (Seignosse), 257 (Biarritz), and 107 (Zarautz); all players reported
  playing, with no unavailable state or player error.
- The permanent HLS MIME instrumentation regression passed on that same build.
- Camera configuration and category file hashes were unchanged after testing.
