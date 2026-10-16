# ONVIF discovery

User-approved scope: a user-triggered, eight-second WS-Discovery search on the
active local Wi-Fi/Ethernet IPv4 network. No port sweep, background monitoring,
automatic login, recording, PTZ, relay, alarm, siren or configuration mutation.
Physical device tests must target Devant only, via a unicast Probe, never a
multicast search of the user's other cameras.

Send only WS-Discovery Probe/NetworkVideoTransmitter to 239.255.255.250:3702,
with TTL 1, a fresh UUID and bounded retransmissions. Bind the socket to the
chosen Android network/interface. Hold a Wi-Fi multicast lock only during the
search and release it on completion, cancellation, background or destruction.

Accept only correlated ProbeMatches with HTTP(S) IPv4 endpoints matching the
response sender. Reject credentials, queries, fragments, public/loopback hosts,
XML entities, malformed and oversized packets. Bound packet count/results and
deduplicate by camera IP. Treat device names as untrusted plain text.

Show results without credentials or further SOAP/RTSP calls. Selecting a result
prefills the existing registration form; the user enters credentials and taps
Connect. Existing cameras are marked rather than registered twice. Keep manual
registration and show useful empty/error/cancellation states.

Verification: JVM parser, correlation, safety, deduplication, UDP fixture and
cancellation tests; Android discovery view/form/lifecycle checks; lint/build;
real unicast Devant check where supported. Emulator multicast does not establish
real phone LAN multicast compatibility.

## Verification, 2026-10-16

- Compilation, unit tests and Android lint pass. Six deterministic discovery
  tests cover payload safety, correlation, names, a local UDP responder,
  deduplication, timeout and cancellation.
- Android checks pass for form prefilling without saving/authenticating,
  already-added results, empty/network states, 320 dp layout and cancellation
  on background. Existing connection/ONVIF checks also pass.
- The opt-in JVM hardware check detects Devant through the actual search engine,
  using only unicast Probes to 192.168.0.109:3702.
- The Android hardware check initially received no packets when selecting the
  first virtual interface. Using the same active-network selection and socket
  binding as the app succeeds, detecting Devant and its port-8000 device service.
- No physical multicast search of other cameras was run. Full Wi-Fi multicast
  reception and router compatibility still require a real-phone acceptance test.
- No camera credentials are sent by discovery. Frigate remains healthy; no
  camera configuration or alarm/siren operation was invoked.

References:
- https://www.onvif.org/wp-content/uploads/2022/07/ONVIF_Profile_S_Client_Test_Specification_22.06.pdf
- https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock
- https://developer.android.com/reference/android/net/Network#bindSocket(java.net.DatagramSocket)
