package IPtProxy

import utls "github.com/refraction-networking/utls"

func init() {
	// Lyrebird's default WebTunnel fingerprint may randomly offer only TLS 1.2.
	// Always advertise TLS 1.3 too, keeping randomized extensions, no ALPN and
	// TLS 1.2 compatibility. Certificate verification stays unchanged.
	weights := utls.DefaultWeights
	weights.TLSVersMax_Set_VersionTLS13 = 1
	utls.HelloRandomizedNoALPN.Weights = &weights
}
