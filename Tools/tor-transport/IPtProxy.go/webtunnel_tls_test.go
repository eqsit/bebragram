package IPtProxy

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"math/big"
	"net"
	"testing"
	"time"

	utls "github.com/refraction-networking/utls"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/common/utlsutil"
)

// Exercise the fingerprint used by Lyrebird against a real Go TLS server with
// ML-KEM enabled. A lost root-module replace regresses this to unsupported HRRs.
func TestWebTunnelRandomizedHandshake(t *testing.T) {
	testWebTunnelHandshake(t, tls.VersionTLS12, tls.VersionTLS13)
}

func TestWebTunnelTLS13OnlyHandshake(t *testing.T) {
	testWebTunnelHandshake(t, tls.VersionTLS13, tls.VersionTLS13)
}

func TestWebTunnelTLS12OnlyHandshake(t *testing.T) {
	testWebTunnelHandshake(t, tls.VersionTLS12, tls.VersionTLS12)
}

func testWebTunnelHandshake(t *testing.T, minVersion, maxVersion uint16) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), DNSNames: []string{"localhost"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots.AddCert(parsed)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	config := &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}},
		MinVersion: minVersion, MaxVersion: maxVersion,
		CurvePreferences: []tls.CurveID{tls.X25519MLKEM768, tls.X25519, tls.CurveP256, tls.CurveP384, tls.CurveP521}}
	results := make(chan error, 1)
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			server := tls.Server(conn, config)
			_ = server.SetDeadline(time.Now().Add(3 * time.Second))
			results <- server.Handshake()
			server.Close()
		}
	}()
	for i := 0; i < 128; i++ {
		conn, err := net.DialTimeout("tcp", listener.Addr().String(), time.Second)
		if err != nil {
			t.Fatal(err)
		}
		seed := utls.PRNGSeed(sha256.Sum256([]byte(fmt.Sprintf("webtunnel-regression-%d", i))))
		fingerprint, err := utlsutil.ParseClientHelloID("hellorandomizednoalpn")
		if err != nil {
			t.Fatal(err)
		}
		id := *fingerprint
		id.Seed = &seed
		client := utls.UClient(conn, &utls.Config{ServerName: "localhost", RootCAs: roots}, id)
		_ = client.SetDeadline(time.Now().Add(3 * time.Second))
		err = client.Handshake()
		client.Close()
		serverErr := <-results
		if err != nil || serverErr != nil {
			t.Fatalf("seed %d: client=%v server=%v", i, err, serverErr)
		}
	}
	t.Log("128/128 randomized WebTunnel TLS handshakes passed with ML-KEM enabled on the server")
}
