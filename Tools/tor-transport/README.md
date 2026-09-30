# Embedded Tor transports

The WebTunnel-only IPtProxy fork is built from source because its Maven AAR resolves upstream uTLS
1.8.2 instead of the uTLS fork requested by Lyrebird. Go ignores `replace`
directives in dependencies: the replacement must be in the root module.

Pinned sources:

- IPtProxy: https://github.com/tladesignz/IPtProxy/tree/d4878bf7729902c1fb5e319d3b043c81388e0720
- Lyrebird: v0.0.0-20260312101154-fc105a03c0e0
- uTLS replacement: gitlab.torproject.org/shelikhoo/utls-temporary v0.0.0-20260114141111-0f042ad603ef
- Go: 1.26.4; gomobile/gobind: v0.0.0-20260611195102-4dd8f1dbf5d2

IPtProxy keeps randomized WebTunnel ClientHello fingerprints while ensuring they
advertise TLS 1.3 support, with TLS 1.2 compatibility and unchanged certificate
verification. This prevents random failures against TLS-1.3-only bridge fronts.
Only the WebTunnel transport is linked. Snowflake client/proxy bindings, WebRTC,
DNSTT and other transport factories are no longer part of the AAR. The vendored
DNSTT directory remains as historical source, not a build dependency.

Connection setup has a single 15-second budget covering DNS, TCP, TLS and HTTP
upgrade. Stopping the transport cancels a pending handshake. Failed handshakes
and failed SOCKS replies close the underlying socket. Established sockets do not
inherit the setup deadline.

The Gradle `buildTorTransport` task builds all four Android ABIs automatically;
CI installs Go and NDK 28.2.13676358. Local prerequisites: Go, JDK 21, Android SDK
and `sdkmanager 'ndk;28.2.13676358'`. Override `ANDROID_NDK_HOME` if needed.
The output AAR lives in `TMessagesProj/build/tor-transport`.

```sh
cd Tools/tor-transport/IPtProxy.go
go test -race -v ./...
```

The regression uses local TLS servers with ML-KEM enabled and 128 deterministic
randomized WebTunnel fingerprints per server: TLS 1.2/1.3, TLS 1.3 only, and TLS
1.2 only. It selects the fingerprint through Lyrebird's production parser.
Certificates are verified against a local test CA. The mixed-version regression
fails with upstream uTLS 1.8.2; the TLS-1.3-only regression also fails with the
unmodified randomized fingerprint. All 384 handshakes pass with the pinned fork
and the TLS-version weight adjustment.

The controller tests use local sockets to verify stalled-handshake deadlines,
shutdown cancellation, socket cleanup on errors and socket survival after a
successful handshake. `go list -deps .` must not include Snowflake or WebRTC.
