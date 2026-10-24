package datapath

import (
	"errors"
	"sync/atomic"
	"syscall"
)

// This edition requires a VPN. NETWORK_UNSPECIFIED must never choose the
// process default route, including while the initial binding is not ready.
const unbound = 0

// networkBinding is the network every new dial is pinned to, swapped live.
// Atomic because the VPN watchdog writes from its own thread while forwarder
// goroutines are dialling.
type networkBinding struct {
	handle atomic.Uint64
}

// control pins each new socket before it connects. Dialer.Control is the only
// window where that takes effect: binding after connect(2) does nothing to a
// socket that has already chosen its route.
//
// An error fails the dial, which is the point -- a socket that could not be
// pinned to the VPN must not quietly leave over the physical network.
func (b *networkBinding) control(network, address string, conn syscall.RawConn) error {
	handle := b.handle.Load()
	if handle == unbound {
		return errors.New("VPN network is not bound; refusing the default route")
	}

	var bindErr error
	if err := conn.Control(func(fd uintptr) {
		bindErr = bindToNetwork(handle, fd)
	}); err != nil {
		return err
	}
	return bindErr
}

func (b *networkBinding) set(handle uint64) {
	b.handle.Store(handle)
}
