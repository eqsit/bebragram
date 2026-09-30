package IPtProxy

import (
	"errors"
	"io"
	"net"
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
