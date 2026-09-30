package IPtProxy

// Embedded WebTunnel transport for Bebragram, derived from IPtProxy.

import (
	"errors"
	"io"
	"io/fs"
	"log"
	"net"
	"net/url"
	"os"
	"path"

	"context"
	"fmt"
	"time"

	pt "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib"
	ptlog "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/common/log"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/transports/base"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/transports/webtunnel"
	"golang.org/x/net/proxy"
)

// LogFileName - the filename of the log residing in `StateDir`.
const LogFileName = "ipt.log"

const Webtunnel = "webtunnel"

// OnTransportEvents - Interface to get notified when the transport stopped again, when errors happened, or when
// the transport actually got a full connection.
//
//goland:noinspection GoUnusedExportedType.
type OnTransportEvents interface {

	// Stopped - Called when the transport stopped again, with or without an error.
	//
	// @param name The transport name that stopped.
	// @param error The error that caused the transport to stop, or nil if the transport stopped without error.
	Stopped(name string, error error)

	// Error reports transport errors.
	//
	// @param name The transport name that errored.
	// @param error The error that occurred.
	Error(name string, error error)

	// Connected means the local transport listener is ready, not a Tor circuit.
	//
	// @param name The transport name that connected.
	Connected(name string)
}

// Controller - Class to start and stop transports.
type Controller struct {
	stateDir        string
	transportEvents OnTransportEvents
	listeners       map[string]*pt.SocksListener
	shutdown        map[string]chan struct{}
}

// NewController - Create a new Controller object.
//
// @param enableLogging Log to StateDir/ipt.log.
//
// @param unsafeLogging Disable the address scrubber.
//
// @param logLevel Log level (ERROR/WARN/INFO/DEBUG). Defaults to ERROR if empty string.
//
// @param transportEvents A delegate, which is called when the transport stopped again, when errors happened, or when
// the transport actually got a full connection.
// Will be called on its own thread! You will need to switch to your own UI thread
// if you want to do UI stuff!
//
//goland:noinspection GoUnusedExportedFunction
func NewController(stateDir string, enableLogging, unsafeLogging bool, logLevel string, transportEvents OnTransportEvents) *Controller {
	c := &Controller{
		stateDir:        stateDir,
		transportEvents: transportEvents,
	}

	if logLevel == "" {
		logLevel = "ERROR"
	}

	if err := createStateDir(c.stateDir); err != nil {
		log.Printf("Failed to set up state directory: %s", err)
		return nil
	}
	if err := ptlog.Init(enableLogging,
		path.Join(c.stateDir, LogFileName), unsafeLogging); err != nil {
		log.Printf("Failed to set initialize log: %s", err.Error())
		return nil
	}
	if err := ptlog.SetLogLevel(logLevel); err != nil {
		log.Printf("Failed to set log level: %s", err.Error())
		ptlog.Warnf("Failed to set log level: %s", err.Error())
	}

	c.listeners = make(map[string]*pt.SocksListener)
	c.shutdown = make(map[string]chan struct{})

	return c
}

// StateDir - The StateDir set in the constructor.
//
// @returns the directory you set in the constructor, where transports store their state and where the log file resides.
func (c *Controller) StateDir() string {
	return c.stateDir
}

// addExtraArgs adds the args in extraArgs to the connection args
func addExtraArgs(conn *pt.SocksConn, extraArgs *pt.Args) {
	if extraArgs == nil {
		return
	}

	if conn.Req.Args == nil {
		conn.Req.Args = make(pt.Args)
	}
	for name := range *extraArgs {
		// Only add if extra arg doesn't already exist, and is not empty.
		if value, ok := conn.Req.Args.Get(name); !ok || value == "" {
			if value, ok := extraArgs.Get(name); ok && value != "" {
				conn.Req.Args.Add(name, value)
			}
		}
	}
}

func acceptLoop(f base.ClientFactory, ln *pt.SocksListener, proxyURL *url.URL,
	extraArgs *pt.Args, shutdown chan struct{}, methodName string, transportEvents OnTransportEvents) {
	defer ln.Close()
	for {
		conn, err := ln.AcceptSocks()
		if err != nil {
			var e net.Error
			if errors.As(err, &e) && !e.Temporary() {
				return
			}

			continue
		}

		go clientHandler(f, conn, proxyURL, extraArgs, shutdown, methodName, transportEvents)
	}
}

func clientHandler(f base.ClientFactory, conn *pt.SocksConn, proxyURL *url.URL,
	extraArgs *pt.Args, shutdown chan struct{}, methodName string, transportEvents OnTransportEvents) {

	defer conn.Close()

	addExtraArgs(conn, extraArgs)
	args, err := f.ParseArgs(&conn.Req.Args)
	if err != nil {
		ptlog.Errorf("Error parsing PT args: %s", err.Error())
		_ = conn.Reject()

		if transportEvents != nil {
			go transportEvents.Stopped(methodName, err)
		}

		return
	}

	remote, err := dialTransport(f, conn.Req.Target, proxyURL, args, shutdown, 15*time.Second)
	if err != nil {
		ptlog.Errorf("Error dialing PT: %s", err.Error())

		if transportEvents != nil {
			go transportEvents.Stopped(methodName, err)
		}

		return
	}

	defer remote.Close()
	err = conn.Grant(&net.TCPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		ptlog.Errorf("conn.Grant error: %s", err)

		if transportEvents != nil {
			go transportEvents.Stopped(methodName, err)
		}

		return
	}

	done := make(chan struct{}, 2)
	go copyLoop(conn, remote, done)

	// Wait for the copy loop to finish or for a shutdown signal.
	select {
	case <-shutdown:
	case <-done:
		ptlog.Noticef("copy loop ended")
	}

	if transportEvents != nil {
		ptlog.Noticef("call OnTransportEvents.Stopped")
		go transportEvents.Stopped(methodName, nil)
	}
}

// Bound DNS, TCP, TLS and the HTTP upgrade together. Closing the raw socket also
// cancels Lyrebird handshakes and fixes its error paths that can leave it open.
func dialTransport(f base.ClientFactory, target string, proxyURL *url.URL, args interface{}, shutdown <-chan struct{}, timeout time.Duration) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	go func() {
		select {
		case <-shutdown:
			cancel()
		case <-ctx.Done():
		}
	}()
	dialer := &net.Dialer{}
	var contextDialer proxy.ContextDialer = dialer
	if proxyURL != nil {
		p, err := proxy.FromURL(proxyURL, dialer)
		if err != nil {
			return nil, err
		}
		var ok bool
		contextDialer, ok = p.(proxy.ContextDialer)
		if !ok {
			return nil, fmt.Errorf("proxy does not support cancellation")
		}
	}
	var raw net.Conn
	var stopClosing func() bool
	remote, err := f.Dial("tcp", target, func(network, address string) (net.Conn, error) {
		conn, err := contextDialer.DialContext(ctx, network, address)
		if err != nil {
			return nil, err
		}
		raw = conn
		stopClosing = context.AfterFunc(ctx, func() { _ = conn.Close() })
		return conn, nil
	}, args)
	if stopClosing != nil {
		stopClosing()
	}
	if err == nil {
		err = ctx.Err()
	}
	if err != nil {
		if raw != nil {
			_ = raw.Close()
		}
		if remote != nil {
			_ = remote.Close()
		}
		return nil, err
	}
	return remote, nil
}

// Exchanges bytes between two ReadWriters.
// (In this case, between a SOCKS connection and a pt conn)
func copyLoop(socks, sfconn io.ReadWriter, done chan struct{}) {
	go func() {
		if _, err := io.Copy(socks, sfconn); err != nil {
			ptlog.Errorf("copying transport to SOCKS resulted in error: %v", err)
		}
		done <- struct{}{}
	}()
	go func() {
		if _, err := io.Copy(sfconn, socks); err != nil {
			ptlog.Errorf("copying SOCKS to transport resulted in error: %v", err)
		}
		done <- struct{}{}
	}()
}

// LocalAddress - Address of the given transport.
//
// @param methodName must be Webtunnel.
//
// @return address string containing host and port where the given transport listens.
func (c *Controller) LocalAddress(methodName string) string {
	if ln, ok := c.listeners[methodName]; ok {
		return ln.Addr().String()
	}
	return ""
}

// Port - Port of the given transport.
//
// @param methodName must be Webtunnel.
//
// @return port number on localhost where the given transport listens.
func (c *Controller) Port(methodName string) int {
	if ln, ok := c.listeners[methodName]; ok {
		return int(ln.Addr().(*net.TCPAddr).AddrPort().Port())
	}
	return 0
}

func createStateDir(path string) error {
	info, err := os.Stat(path)

	// If dir does not exist, try to create it.
	if errors.Is(err, os.ErrNotExist) {
		err = os.MkdirAll(path, 0700)

		if err == nil {
			info, err = os.Stat(path)
		}
	}

	// If it is not a dir, return error
	if err == nil && !info.IsDir() {
		err = fs.ErrInvalid
		return err
	}

	// Create a file within dir to test writability.
	tempFile := path + "/.iptproxy-writetest"
	var file *os.File
	file, err = os.Create(tempFile)

	// Remove the test file again.
	if err == nil {
		_ = file.Close()

		err = os.Remove(tempFile)
	}
	return err
}

// Start - Start given transport.
//
// @param methodName must be Webtunnel.
//
// @param proxy HTTP, SOCKS4 or SOCKS5 proxy to be used behind Lyrebird. E.g. "socks5://127.0.0.1:12345"
//
// @throws if the proxy URL cannot be parsed, if the given `methodName` cannot be found, if the transport cannot
// be initialized, or if it couldn't bind a port for listening.
func (c *Controller) Start(methodName string, proxy string) error {
	var proxyURL *url.URL
	var err error

	if proxy != "" {
		proxyURL, err = url.Parse(proxy)
		if err != nil {
			ptlog.Errorf("Failed to parse proxy address: %s", err.Error())
			return err
		}
	}

	if methodName != Webtunnel {
		return fmt.Errorf("unsupported transport: %s", methodName)
	}
	if _, exists := c.listeners[methodName]; exists {
		return nil
	}
	f, err := webtunnel.Transport.ClientFactory(c.stateDir)
	if err != nil {
		return err
	}
	ln, err := pt.ListenSocks("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	c.listeners[methodName] = ln
	c.shutdown[methodName] = make(chan struct{})
	go acceptLoop(f, ln, proxyURL, nil, c.shutdown[methodName], methodName, c.transportEvents)
	if c.transportEvents != nil {
		go c.transportEvents.Connected(methodName)
	}

	ptlog.Noticef("Launched transport: %v", methodName)

	return nil
}

// Stop - Stop given transport.
//
// @param methodName must be Webtunnel.
func (c *Controller) Stop(methodName string) {
	if ln, ok := c.listeners[methodName]; ok {
		_ = ln.Close()

		ptlog.Noticef("Shutting down %s", methodName)

		close(c.shutdown[methodName])
		delete(c.shutdown, methodName)
		delete(c.listeners, methodName)
	} else {
		ptlog.Warnf("No listener for %s", methodName)
	}
}

// LyrebirdVersion - The version of Lyrebird bundled with IPtProxy.
//
//goland:noinspection GoUnusedExportedFunction
func LyrebirdVersion() string {
	return "lyrebird-0.8.1"
}
