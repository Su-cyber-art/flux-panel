package udp

import (
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/go-gost/core/common/bufpool"
	"github.com/go-gost/core/logger"
)

var errRecvQueueFull = errors.New("recv queue is full")

type ListenConfig struct {
	Addr           net.Addr
	Backlog        int
	ReadQueueSize  int
	ReadBufferSize int
	TTL            time.Duration
	Keepalive      bool
	Logger         logger.Logger
}
type listener struct {
	conn      net.PacketConn
	cqueue    chan net.Conn
	connPool  *connPool
	closed    chan struct{}
	done      chan struct{}
	closeOnce sync.Once
	errChan   chan error
	config    *ListenConfig
}

func NewListener(conn net.PacketConn, cfg *ListenConfig) net.Listener {
	if cfg == nil {
		cfg = &ListenConfig{}
	}

	ln := &listener{
		conn:    conn,
		cqueue:  make(chan net.Conn, cfg.Backlog),
		closed:  make(chan struct{}),
		done:    make(chan struct{}),
		errChan: make(chan error, 1),
		config:  cfg,
	}
	ln.connPool = newConnPool(cfg.TTL).WithLogger(cfg.Logger)
	go ln.listenLoop()

	return ln
}

func (ln *listener) Accept() (conn net.Conn, err error) {
	select {
	case conn = <-ln.cqueue:
		return
	case <-ln.closed:
		return nil, net.ErrClosed
	case err = <-ln.errChan:
		if err == nil {
			err = net.ErrClosed
		}
		return
	}
}

func (ln *listener) listenLoop() {
	defer close(ln.done)
	// Reuse the receive buffer for small packets, which are copied into
	// smaller size classes. Large packets transfer ownership without a copy.
	b := bufpool.Get(ln.config.ReadBufferSize)
	defer func() { bufpool.Put(b) }()
	for {
		select {
		case <-ln.closed:
			return
		default:
		}

		n, raddr, err := ln.conn.ReadFrom(b)
		if err != nil {
			ln.errChan <- err
			close(ln.errChan)
			return
		}

		c := ln.getConn(raddr)
		if c == nil {
			continue
		}

		if n > cap(b)/2 {
			if err := c.writeOwnedQueue(b[:n]); err != nil {
				ln.config.Logger.Warn("data discarded: ", err)
			} else {
				b = bufpool.Get(ln.config.ReadBufferSize)
			}
		} else if err := c.WriteQueue(b[:n]); err != nil {
			ln.config.Logger.Warn("data discarded: ", err)
		}
	}
}

func (ln *listener) Addr() net.Addr {
	if ln.config.Addr != nil {
		return ln.config.Addr
	}
	return ln.conn.LocalAddr()
}

func (ln *listener) Close() error {
	ln.closeOnce.Do(func() {
		close(ln.closed)
		ln.conn.Close()
		// A read already in flight may still create a client. Wait for it
		// before closing the pool so every pending packet is released.
		<-ln.done
		ln.connPool.Close()
	})

	return nil
}

func (ln *listener) getConn(raddr net.Addr) *conn {
	c, ok := ln.connPool.Get(raddr.String())
	if ok && !c.isClosed() {
		return c
	}

	c = newConn(ln.conn, ln.Addr(), raddr, ln.config.ReadQueueSize, ln.config.Keepalive)
	select {
	case ln.cqueue <- c:
		ln.connPool.Set(raddr.String(), c)
		return c
	default:
		c.Close()
		ln.config.Logger.Warnf("connection queue is full, client %s discarded", raddr)
		return nil
	}
}

// conn is a server side connection for UDP client peer, it implements net.Conn and net.PacketConn.
type conn struct {
	net.PacketConn
	localAddr  net.Addr
	remoteAddr net.Addr
	rc         chan []byte // data receive queue
	idle       int32       // indicate the connection is idle
	closed     chan struct{}
	closeMutex sync.Mutex
	keepalive  bool
}

func newConn(c net.PacketConn, laddr, remoteAddr net.Addr, queueSize int, keepalive bool) *conn {
	return &conn{
		PacketConn: c,
		localAddr:  laddr,
		remoteAddr: remoteAddr,
		rc:         make(chan []byte, queueSize),
		closed:     make(chan struct{}),
		keepalive:  keepalive,
	}
}

func (c *conn) ReadFrom(b []byte) (n int, addr net.Addr, err error) {
	select {
	case bb := <-c.rc:
		n = copy(b, bb)
		c.SetIdle(false)
		bufpool.Put(bb)

	case <-c.closed:
		err = net.ErrClosed
		return
	}

	addr = c.remoteAddr

	return
}

func (c *conn) Read(b []byte) (n int, err error) {
	n, _, err = c.ReadFrom(b)
	return
}

func (c *conn) WriteTo(b []byte, addr net.Addr) (n int, err error) {
	if !c.keepalive {
		defer c.Close()
	}
	return c.PacketConn.WriteTo(b, addr)
}

func (c *conn) Write(b []byte) (n int, err error) {
	return c.WriteTo(b, c.remoteAddr)
}

func (c *conn) Close() error {
	c.closeMutex.Lock()
	defer c.closeMutex.Unlock()

	select {
	case <-c.closed:
		return nil
	default:
		close(c.closed)
	}
	// Enqueues share closeMutex, so no packet can arrive after this drain.
	for {
		select {
		case b := <-c.rc:
			bufpool.Put(b)
		default:
			return nil
		}
	}
}

func (c *conn) isClosed() bool {
	select {
	case <-c.closed:
		return true
	default:
		return false
	}
}

func (c *conn) LocalAddr() net.Addr {
	return c.localAddr
}

func (c *conn) RemoteAddr() net.Addr {
	return c.remoteAddr
}

func (c *conn) IsIdle() bool {
	return atomic.LoadInt32(&c.idle) > 0
}

func (c *conn) SetIdle(idle bool) {
	v := int32(0)
	if idle {
		v = 1
	}
	atomic.StoreInt32(&c.idle, v)
}

func (c *conn) WriteQueue(b []byte) error {
	return c.queueDatagram(b, true)
}

// writeOwnedQueue takes ownership of b only when it succeeds. On failure,
// the listener can reuse its receive buffer without an allocation.
func (c *conn) writeOwnedQueue(b []byte) error {
	return c.queueDatagram(b, false)
}

func (c *conn) queueDatagram(b []byte, copyPayload bool) error {
	c.closeMutex.Lock()
	defer c.closeMutex.Unlock()
	if c.isClosed() {
		return net.ErrClosed
	}
	// Avoid allocating and copying packets that cannot enter a full queue.
	// An unbuffered queue may still have a waiting reader.
	if cap(c.rc) > 0 && len(c.rc) == cap(c.rc) {
		return errRecvQueueFull
	}
	packet := b
	if copyPayload {
		packet = bufpool.Get(len(b))
		copy(packet, b)
	}
	select {
	case c.rc <- packet:
		return nil
	default:
		if copyPayload {
			bufpool.Put(packet)
		}
		return errRecvQueueFull
	}
}
