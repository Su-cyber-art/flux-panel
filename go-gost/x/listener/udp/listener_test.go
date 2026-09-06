package udp

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"runtime"
	"testing"
	"time"

	"github.com/go-gost/core/listener"
	"github.com/go-gost/x/logger"
	md "github.com/go-gost/x/metadata"
)

func TestListenerPreservesLargeDatagrams(t *testing.T) {
	for _, explicitBuffer := range []bool{false, true} {
		t.Run(fmt.Sprintf("explicit_buffer=%t", explicitBuffer), func(t *testing.T) {
			settings := map[string]any{"keepAlive": true}
			if explicitBuffer {
				settings["readBufferSize"] = "65536"
			}
			// Exercise the same JSON metadata types used by panel commands and
			// persisted node configuration, rather than an in-memory int value.
			encoded, err := json.Marshal(settings)
			if err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal(encoded, &settings); err != nil {
				t.Fatal(err)
			}
			ln := NewListener(
				listener.AddrOption("127.0.0.1:0"),
				listener.LoggerOption(logger.Nop()),
			)
			if err := ln.Init(md.NewMetadata(settings)); err != nil {
				t.Fatal(err)
			}
			done := make(chan struct{})
			go func() {
				defer close(done)
				conn, err := ln.Accept()
				if err != nil {
					return
				}
				defer conn.Close()
				buffer := make([]byte, 65536)
				for {
					n, err := conn.Read(buffer)
					if err != nil {
						return
					}
					if _, err := conn.Write(buffer[:n]); err != nil {
						return
					}
				}
			}()
			t.Cleanup(func() {
				ln.Close()
				select {
				case <-done:
				case <-time.After(3 * time.Second):
					t.Error("UDP echo did not stop")
				}
			})

			client, err := net.Dial("udp", ln.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			sizes := []int{32, 1200, 8192, 8193, 9000}
			// Linux CI also exercises the maximum IPv4 UDP payload. macOS's
			// default UDP send limit is smaller; 9000 still catches the old cap.
			if runtime.GOOS == "linux" {
				sizes = append(sizes, 65507)
			}
			for _, size := range sizes {
				t.Run(fmt.Sprintf("bytes=%d", size), func(t *testing.T) {
					payload := make([]byte, size)
					for i := range payload {
						payload[i] = byte(i*31 + size)
					}
					if err := client.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
						t.Fatal(err)
					}
					if _, err := client.Write(payload); err != nil {
						t.Fatal(err)
					}
					received := make([]byte, 65536)
					n, err := client.Read(received)
					if err != nil {
						t.Fatal(err)
					}
					if !bytes.Equal(payload, received[:n]) {
						t.Fatalf("UDP payload changed: sent %d bytes, received %d", size, n)
					}
				})
			}
		})
	}
}
