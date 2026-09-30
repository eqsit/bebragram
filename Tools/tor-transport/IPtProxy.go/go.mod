module github.com/tladesignz/IPtProxy.git

go 1.25.0

tool golang.org/x/mobile/cmd/gomobile

require (
	github.com/refraction-networking/utls v1.8.2
	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib v1.6.0
	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird v0.0.0-20260312101154-fc105a03c0e0
	golang.org/x/net v0.56.0
)

require (
	github.com/andybalholm/brotli v1.2.0 // indirect
	github.com/cloudflare/circl v1.5.0 // indirect
	github.com/google/go-cmp v0.7.0 // indirect
	github.com/klauspost/compress v1.18.3 // indirect
	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/webtunnel v0.0.3 // indirect
	golang.org/x/crypto v0.53.0 // indirect
	golang.org/x/mobile v0.0.0-20260611195102-4dd8f1dbf5d2 // indirect
	golang.org/x/mod v0.37.0 // indirect
	golang.org/x/sync v0.21.0 // indirect
	golang.org/x/sys v0.46.0 // indirect
	golang.org/x/tools v0.46.0 // indirect
)

replace github.com/refraction-networking/utls => gitlab.torproject.org/shelikhoo/utls-temporary v0.0.0-20260114141111-0f042ad603ef
