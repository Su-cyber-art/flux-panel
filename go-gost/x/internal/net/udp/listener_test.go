package udp

import (
	"bytes"
	"errors"
	"fmt"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/core/common/bufpool"
	"github.com/go-gost/x/logger"
)

type testDatagram struct {
	data []byte
	peer net.Addr
}

// A synchronous packet source makes queue occupancy deterministic without
// depending on the operating system's UDP send limits or packet loss.
type testPacketConn struct {
	packets     chan testDatagram
	readStarted chan struct{}
	closed      chan struct{}
	closeOnce   sync.Once
}

func (c *testPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	select {
	case c.readStarted <- struct{}{}:
	case <-c.closed:
		return 0, nil, net.ErrClosed
	}
	select {
	case packet := <-c.packets:
		return copy(b, packet.data), packet.peer, nil
	case <-c.closed:
		return 0, nil, net.ErrClosed
	}
}

func (c *testPacketConn) WriteTo(b []byte, _ net.Addr) (int, error) { return len(b), nil }
func (c *testPacketConn) LocalAddr() net.Addr                       { return &net.UDPAddr{Port: 9000} }
func (c *testPacketConn) SetDeadline(time.Time) error               { return nil }
func (c *testPacketConn) SetReadDeadline(time.Time) error           { return nil }
func (c *testPacketConn) SetWriteDeadline(time.Time) error          { return nil }
func (c *testPacketConn) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	return nil
}

func newTestListener(t testing.TB, queueSize, backlog int) (*listener, *testPacketConn) {
	t.Helper()
	pc := &testPacketConn{
		packets: make(chan testDatagram), readStarted: make(chan struct{}),
		closed: make(chan struct{}),
	}
	ln := NewListener(pc, &ListenConfig{
		Backlog: backlog, ReadQueueSize: queueSize, ReadBufferSize: 65536,
		TTL: time.Hour, Keepalive: true, Logger: logger.Nop(),
	}).(*listener)
	t.Cleanup(func() { ln.Close() })
	waitForRead(t, pc)
	return ln, pc
}

func waitForRead(t testing.TB, pc *testPacketConn) {
	t.Helper()
	select {
	case <-pc.readStarted:
	case <-time.After(3 * time.Second):
		t.Fatal("listener did not request the next datagram")
	}
}

func feedDatagram(t testing.TB, pc *testPacketConn, data []byte, peer net.Addr) {
	t.Helper()
	select {
	case pc.packets <- testDatagram{data: data, peer: peer}:
	case <-time.After(3 * time.Second):
		t.Fatal("listener did not receive the datagram")
	}
	// The next read only starts after the previous datagram has been enqueued.
	waitForRead(t, pc)
}

func TestListenerQueuesDatagramsByPayloadSize(t *testing.T) {
	for _, size := range []int{0, 64, 1200, 8193, 9000, 65507} {
		t.Run(fmt.Sprintf("bytes=%d", size), func(t *testing.T) {
			ln, pc := newTestListener(t, 2, 1)
			peer := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 12345}
			first := bytes.Repeat([]byte{0x3a}, size)
			second := bytes.Repeat([]byte{0xc5}, size)
			feedDatagram(t, pc, first, peer)
			feedDatagram(t, pc, second, peer)
			c, ok := ln.connPool.Get(peer.String())
			if !ok {
				t.Fatal("missing client connection")
			}
			for _, want := range [][]byte{first, second} {
				queued := <-c.rc
				if !bytes.Equal(queued, want) {
					t.Error("reading the next datagram overwrote a queued payload")
				}
				// The existing size-class pool may round up, but small packets
				// must not retain the full 64 KiB receive buffer.
				if cap(queued) > max(128, size*2) {
					t.Errorf("%d-byte payload retains %d bytes", size, cap(queued))
				}
				bufpool.Put(queued)
			}
		})
	}
}

func TestListenerBoundsBackloggedClientMemory(t *testing.T) {
	const clients, queueSize, payloadSize = 32, 128, 1200
	ln, pc := newTestListener(t, queueSize, clients)
	var held int
	for client := 0; client < clients; client++ {
		peer := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 10000 + client}
		payload := bytes.Repeat([]byte{byte(client)}, payloadSize)
		for i := 0; i < queueSize+1; i++ {
			feedDatagram(t, pc, payload, peer)
		}
		c, _ := ln.connPool.Get(peer.String())
		if c == nil || len(c.rc) != queueSize {
			t.Fatal("client queue did not stay at its configured limit")
		}
	}
	for client := 0; client < clients; client++ {
		c := (<-ln.cqueue).(*conn)
		for i := 0; i < queueSize; i++ {
			queued := <-c.rc
			held += cap(queued)
			bufpool.Put(queued)
		}
	}
	if limit := clients * queueSize * 2048; held > limit {
		t.Errorf("queued buffers hold %d bytes, want at most %d", held, limit)
	}
	t.Logf("%d clients x %d queued %d-byte packets: %d bytes in payload buffers",
		clients, queueSize, payloadSize, held)
}

func TestListenerReusesBufferAfterQueueOverflow(t *testing.T) {
	for _, size := range []int{1200, 65507} {
		t.Run(fmt.Sprintf("bytes=%d", size), func(t *testing.T) {
			ln, pc := newTestListener(t, 1, 1)
			peer := &net.UDPAddr{Port: 12345}
			first := bytes.Repeat([]byte{0x1a}, size)
			feedDatagram(t, pc, first, peer)
			// Drop a different packet while the queue is full. It must not
			// overwrite a pending packet or poison the next receive buffer.
			feedDatagram(t, pc, bytes.Repeat([]byte{0x2b}, size), peer)
			c, _ := ln.connPool.Get(peer.String())
			buffer := make([]byte, 65536)
			if n, _, err := c.ReadFrom(buffer); err != nil || !bytes.Equal(buffer[:n], first) {
				t.Fatal("queue overflow corrupted the pending datagram")
			}
			third := bytes.Repeat([]byte{0x3c}, size)
			feedDatagram(t, pc, third, peer)
			if n, _, err := c.ReadFrom(buffer); err != nil || !bytes.Equal(buffer[:n], third) {
				t.Fatal("receive buffer was not reusable after queue overflow")
			}
		})
	}
}

func TestConnCloseDiscardsPendingDatagrams(t *testing.T) {
	c := newConn(nil, nil, &net.UDPAddr{Port: 12345}, 4, true)
	for i := 0; i < 4; i++ {
		if err := c.WriteQueue([]byte("queued")); err != nil {
			t.Fatal(err)
		}
	}
	c.Close()
	if len(c.rc) != 0 {
		t.Errorf("closed connection still retains %d packets", len(c.rc))
	}
	for i := 0; i < 32; i++ {
		if err := c.WriteQueue([]byte("late")); !errors.Is(err, net.ErrClosed) {
			t.Fatalf("enqueue after close: %v", err)
		}
	}
	if _, _, err := c.ReadFrom(make([]byte, 32)); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("read after close: %v", err)
	}
	// Keep baseline failures from retaining their unconsumed test buffers.
	for len(c.rc) > 0 {
		bufpool.Put(<-c.rc)
	}
}

func TestConnConcurrentReadQueueAndClose(t *testing.T) {
	for round := 0; round < 32; round++ {
		c := newConn(nil, nil, &net.UDPAddr{Port: 12345}, 32, true)
		var workers sync.WaitGroup
		started := make(chan struct{}, 4)
		for writer := 0; writer < 4; writer++ {
			workers.Add(1)
			go func() {
				defer workers.Done()
				payload := make([]byte, 1200)
				c.WriteQueue(payload)
				started <- struct{}{}
				for i := 0; i < 128; i++ {
					c.WriteQueue(payload)
				}
			}()
		}
		workers.Add(1)
		go func() {
			defer workers.Done()
			buffer := make([]byte, 2048)
			for {
				if _, err := c.Read(buffer); err != nil {
					return
				}
			}
		}()
		for i := 0; i < 4; i++ {
			<-started
		}
		for i := 0; i < 4; i++ {
			workers.Add(1)
			go func() { defer workers.Done(); c.Close() }()
		}
		workers.Wait()
		if len(c.rc) != 0 {
			t.Fatalf("close raced with enqueue: %d packets retained", len(c.rc))
		}
	}
}

func TestListenerConcurrentClose(t *testing.T) {
	ln, pc := newTestListener(t, 4, 1)
	peer := &net.UDPAddr{Port: 12345}
	feedDatagram(t, pc, []byte("pending"), peer)
	c, _ := ln.connPool.Get(peer.String())
	var workers sync.WaitGroup
	for i := 0; i < 16; i++ {
		workers.Add(1)
		go func() { defer workers.Done(); ln.Close() }()
	}
	workers.Wait()
	if len(c.rc) != 0 {
		t.Fatal("listener shutdown retained queued payloads")
	}
}

func BenchmarkListenerReceive(b *testing.B) {
	for _, size := range []int{64, 1200, 9000, 65507} {
		b.Run(fmt.Sprintf("bytes=%d", size), func(b *testing.B) {
			ln, pc := newTestListener(b, 128, 1)
			packet := testDatagram{data: make([]byte, size), peer: &net.UDPAddr{Port: 12345}}
			feedDatagram(b, pc, packet.data, packet.peer)
			c := (<-ln.cqueue).(*conn)
			queued := <-c.rc
			queuedCapacity := cap(queued)
			bufpool.Put(queued)
			buffer := make([]byte, 65536)
			b.ReportAllocs()
			b.SetBytes(int64(size))
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				pc.packets <- packet
				<-pc.readStarted
				if n, _, err := c.ReadFrom(buffer); err != nil || n != size {
					b.Fatalf("received %d bytes: %v", n, err)
				}
			}
			b.ReportMetric(float64(queuedCapacity), "queued-B/packet")
		})
	}
}
