package IPtProxy

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	pt "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/transports/base"
)

type testFactory struct {
	address   string
	handshake func(net.Conn) error
}

func (f *testFactory) Transport() base.Transport               { return nil }
func (f *testFactory) ParseArgs(*pt.Args) (interface{}, error) { return nil, nil }
func (f *testFactory) OnEvent(func(base.TransportEvent))       {}
func (f *testFactory) Dial(_, _ string, dial base.DialFunc, _ interface{}) (net.Conn, error) {
	conn, err := dial("tcp", f.address)
	if err != nil {
		return nil, err
	}
	if f.handshake != nil {
		if err := f.handshake(conn); err != nil {
			return nil, err
		}
	}
	return conn, nil
}

func localEndpoint(t *testing.T) (string, <-chan net.Conn) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	accepted := make(chan net.Conn, 1)
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			accepted <- conn
		}
	}()
	return listener.Addr().String(), accepted
}

func TestDialHandshakeDeadline(t *testing.T) {
	address, accepted := localEndpoint(t)
	f := &testFactory{address: address, handshake: func(conn net.Conn) error {
		_, err := conn.Read(make([]byte, 1))
		return err
	}}
	start := time.Now()
	_, err := dialTransport(f, "unused", nil, nil, make(chan struct{}), 100*time.Millisecond)
	if err == nil || time.Since(start) > time.Second {
		t.Fatalf("handshake deadline: %v", err)
	}
	peer := <-accepted
	defer peer.Close()
	peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := peer.Read(make([]byte, 1)); err != io.EOF {
		t.Fatalf("socket leaked: %v", err)
	}
}

func TestDialShutdownCancelsHandshake(t *testing.T) {
	address, accepted := localEndpoint(t)
	entered := make(chan struct{})
	shutdown := make(chan struct{})
	f := &testFactory{address: address, handshake: func(conn net.Conn) error {
		close(entered)
		_, err := conn.Read(make([]byte, 1))
		return err
	}}
	result := make(chan error, 1)
	go func() { _, err := dialTransport(f, "unused", nil, nil, shutdown, time.Minute); result <- err }()
	<-entered
	close(shutdown)
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("cancelled handshake succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("shutdown did not cancel the handshake")
	}
	(<-accepted).Close()
}

func TestDialFailureClosesUnderlyingSocket(t *testing.T) {
	address, accepted := localEndpoint(t)
	f := &testFactory{address: address, handshake: func(net.Conn) error { return errors.New("invalid certificate") }}
	if _, err := dialTransport(f, "unused", nil, nil, make(chan struct{}), time.Second); err == nil {
		t.Fatal("expected failure")
	}
	peer := <-accepted
	defer peer.Close()
	peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := peer.Read(make([]byte, 1)); err != io.EOF {
		t.Fatalf("socket leaked: %v", err)
	}
}

func TestDialSuccessKeepsSocketOpen(t *testing.T) {
	address, accepted := localEndpoint(t)
	conn, err := dialTransport(&testFactory{address: address}, "unused", nil, nil, make(chan struct{}), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	peer := <-accepted
	defer peer.Close()
	if _, err := conn.Write([]byte{42}); err != nil {
		t.Fatal(err)
	}
	peer.SetReadDeadline(time.Now().Add(time.Second))
	b := make([]byte, 1)
	if _, err := io.ReadFull(peer, b); err != nil || b[0] != 42 {
		t.Fatalf("successful socket closed: %v", err)
	}
}

func TestProbeRequiresWebTunnelUpgrade(t *testing.T) {
	for _, status := range []int{200, 404, 101} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Connection") != "upgrade" || r.Header.Get("Upgrade") != "websocket" || r.URL.Path != "/secret" {
					t.Errorf("probe did not use the WebTunnel handshake")
				}
				if status == 101 {
					w.Header().Set("Connection", "upgrade")
					w.Header().Set("Upgrade", "websocket")
				}
				w.WriteHeader(status)
			}))
			defer server.Close()
			c := &Controller{stateDir: t.TempDir()}
			_, err := c.ProbeWebtunnel("webtunnel 192.0.2.1:443 url=http://bridge.example/secret addr="+server.Listener.Addr().String(), 1000)
			if (err == nil) != (status == 101) {
				t.Fatalf("HTTP %d: probe error %v", status, err)
			}
		})
	}
}

func TestProbeVerifiesTLSCertificate(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Connection", "upgrade")
		w.Header().Set("Upgrade", "websocket")
		w.WriteHeader(101)
	}))
	defer server.Close()
	c := &Controller{stateDir: t.TempDir()}
	line := "webtunnel 192.0.2.1:443 url=https://bridge.example/secret addr=" + server.Listener.Addr().String()
	// A self-signed certificate must fail unless the supplied bridge pins it.
	if _, err := c.ProbeWebtunnel(line, 1000); err == nil {
		t.Fatal("untrusted certificate accepted without a pin")
	}
	hash := sha256.Sum256(server.Certificate().Raw)
	if _, err := c.ProbeWebtunnel(line+" cert="+base64.StdEncoding.EncodeToString(hash[:]), 1000); err != nil {
		t.Fatalf("correct certificate pin failed: %v", err)
	}
	hash[0] ^= 1
	if _, err := c.ProbeWebtunnel(line+" cert="+base64.StdEncoding.EncodeToString(hash[:]), 1000); err == nil {
		t.Fatal("wrong certificate pin accepted")
	}
}

func TestProbeStalledUpgradeTimesOut(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()
	c := &Controller{stateDir: t.TempDir()}
	start := time.Now()
	_, err := c.ProbeWebtunnel("webtunnel 192.0.2.1:443 url=http://bridge.example/secret addr="+server.Listener.Addr().String(), 100)
	if !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > time.Second {
		t.Fatalf("stalled HTTP upgrade: %v", err)
	}
}
