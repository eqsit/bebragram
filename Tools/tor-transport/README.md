# Embedded Tor transports

IPtProxy 5.5.1 is built from source because its Maven AAR resolves upstream uTLS
1.8.2 instead of the uTLS fork requested by Lyrebird. Go ignores `replace`
directives in dependencies: the replacement must be in the root module.

Pinned sources:

- IPtProxy: https://github.com/tladesignz/IPtProxy/tree/d4878bf7729902c1fb5e319d3b043c81388e0720
- DNSTT: https://github.com/tladesignz/dnstt/tree/f1b9b97a269f83bad41d2ceef291b4d2c161cd11
- Lyrebird: v0.0.0-20260312101154-fc105a03c0e0
- uTLS replacement: gitlab.torproject.org/shelikhoo/utls-temporary v0.0.0-20260114141111-0f042ad603ef
- Go: 1.26.4; gomobile/gobind: v0.0.0-20260611195102-4dd8f1dbf5d2

IPtProxy sources are copied unchanged. DNSTT fixes a reversed `errors.As` call in
the SOCKS accept loop so temporary network errors are detected correctly. The root
module also adds the uTLS replacement, checksums and TLS regression test. Licenses
are included.

The Gradle `buildTorTransport` task builds all four Android ABIs automatically;
CI installs Go and NDK 28.2.13676358. Local prerequisites: Go, JDK 21, Android SDK
and `sdkmanager 'ndk;28.2.13676358'`. Override `ANDROID_NDK_HOME` if needed.
The output AAR lives in `TMessagesProj/build/tor-transport`.

```sh
cd Tools/tor-transport/IPtProxy.go
go test -v ./...
cd ../dnstt
go test ./...
```

The regression uses a local TLS server with ML-KEM enabled and 128 deterministic
randomized WebTunnel fingerprints. Certificates are verified against a local test
CA. It fails with upstream uTLS 1.8.2 and passes with the pinned Tor fork.
