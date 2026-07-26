package socket

import (
	"fmt"
	"net"
	"testing"
	"time"
)

func loopbackTarget(port int) string {
	return net.JoinHostPort("127.0.0.1", fmt.Sprintf("%d", port))
}

func TestEchoRelayRoundTripForwardsEndToEndToken(t *testing.T) {
	reporter := &WebSocketReporter{}
	echo, err := reporter.handleEchoServer(EchoServerRequest{DurationMs: 5_000})
	if err != nil {
		t.Fatalf("start echo server: %v", err)
	}
	defer diagStop(echo.Token)

	relay, err := reporter.handleEchoRelay(EchoRelayRequest{
		NextHop:    loopbackTarget(echo.Port),
		DurationMs: 5_000,
	})
	if err != nil {
		t.Fatalf("start echo relay: %v", err)
	}
	defer diagStop(relay.Token)

	resp, err := reporter.handleEchoProbe(EchoProbeRequest{
		Target:      loopbackTarget(relay.Port),
		Token:       echo.Token,
		Rounds:      3,
		PayloadSize: 256,
		TimeoutMs:   2_000,
	})
	if err != nil {
		t.Fatalf("probe relay: %v", err)
	}
	if !resp.Success {
		t.Fatalf("expected successful round trip, got: %+v", resp)
	}
	if !resp.IntegrityOk || resp.OkRounds != 3 || resp.BytesVerified != 768 {
		t.Fatalf("unexpected verification metrics: %+v", resp)
	}
}

func TestEchoRelayRejectsWrongToken(t *testing.T) {
	reporter := &WebSocketReporter{}
	echo, err := reporter.handleEchoServer(EchoServerRequest{DurationMs: 5_000})
	if err != nil {
		t.Fatalf("start echo server: %v", err)
	}
	defer diagStop(echo.Token)

	relay, err := reporter.handleEchoRelay(EchoRelayRequest{
		NextHop:    loopbackTarget(echo.Port),
		DurationMs: 5_000,
	})
	if err != nil {
		t.Fatalf("start echo relay: %v", err)
	}
	defer diagStop(relay.Token)

	resp, err := reporter.handleEchoProbe(EchoProbeRequest{
		Target:      loopbackTarget(relay.Port),
		Token:       "00000000000000000000000000000000",
		Rounds:      1,
		PayloadSize: 32,
		TimeoutMs:   500,
	})
	if err != nil {
		t.Fatalf("probe relay: %v", err)
	}
	if resp.Success {
		t.Fatalf("relay accepted an invalid token: %+v", resp)
	}
}

func TestEchoRelayRequiresNextHop(t *testing.T) {
	reporter := &WebSocketReporter{}
	if _, err := reporter.handleEchoRelay(EchoRelayRequest{}); err == nil {
		t.Fatal("expected missing next hop to be rejected")
	}
}

func TestTcpPingCollectsRealConnections(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			conn, acceptErr := ln.Accept()
			if acceptErr != nil {
				return
			}
			_ = conn.Close()
		}
	}()

	addr := ln.Addr().(*net.TCPAddr)
	stat := tcpPingCollect("127.0.0.1", addr.Port, 3, 1_000, 4_000)
	if stat.err != nil {
		t.Fatalf("tcp ping failed: %v", stat.err)
	}
	if stat.attempts != 3 || stat.success != 3 || stat.loss != 0 {
		t.Fatalf("unexpected tcp ping metrics: %+v", stat)
	}

	_ = ln.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("accept loop did not stop")
	}
}
