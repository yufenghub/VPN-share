//go:build android

package datapath

import (
	"net"
	"strings"
	"testing"
	"time"
)

// Exercise Android's real socket binding: an unavailable VPN handle must fail
// before connecting, even when the target is reachable on the default route.
func TestInvalidPinnedNetworkCannotUseDefaultRoute(t *testing.T) {
	testNoDefaultRoute(t, ^uint64(0), "bindToNetwork")
}

func TestUnboundDatapathCannotUseDefaultRoute(t *testing.T) {
	testNoDefaultRoute(t, 0, "refusing the default route")
}

func testNoDefaultRoute(t *testing.T, handle uint64, expectedError string) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	binding := &networkBinding{}
	binding.set(handle)
	dialer := net.Dialer{Timeout: time.Second, Control: binding.control}
	conn, err := dialer.Dial("tcp", listener.Addr().String())
	if err == nil {
		conn.Close()
		t.Fatal("invalid pinned network used the default route")
	}
	if !strings.Contains(err.Error(), expectedError) {
		t.Fatalf("expected Android network binding failure, got %v", err)
	}
	if err := listener.(*net.TCPListener).SetDeadline(time.Now().Add(50 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	unexpected, err := listener.Accept()
	if err == nil {
		unexpected.Close()
		t.Fatal("a connection reached the default-route listener")
	}
	if timeout, ok := err.(net.Error); !ok || !timeout.Timeout() {
		t.Fatalf("expected no connection to reach the listener, got %v", err)
	}
}
