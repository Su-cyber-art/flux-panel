package service

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSendTrafficReportUsesBatchContract(t *testing.T) {
	var received TrafficReportBatch
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/flow/upload/batch" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&received); err != nil {
			t.Fatalf("decode request: %v", err)
		}
		_, _ = w.Write([]byte("ok"))
	}))
	defer server.Close()

	previousURL := httpReportURL
	previousCrypto := httpAESCrypto
	httpReportURL = server.URL + "/flow/upload/batch"
	httpAESCrypto = nil
	defer func() {
		httpReportURL = previousURL
		httpAESCrypto = previousCrypto
	}()

	report := TrafficReportBatch{
		InstanceID: "instance-a",
		StartedAt:  1000,
		Sequence:   7,
		Items: []TrafficReportItem{
			{N: "101_7_501", G: 900, U: 120, D: 340},
		},
	}
	success, err := sendTrafficReport(context.Background(), report)
	if err != nil {
		t.Fatalf("send report: %v", err)
	}
	if !success {
		t.Fatal("expected successful acknowledgement")
	}
	if received.InstanceID != report.InstanceID || received.Sequence != report.Sequence {
		t.Fatalf("unexpected batch metadata: %#v", received)
	}
	if len(received.Items) != 1 || received.Items[0] != report.Items[0] {
		t.Fatalf("unexpected batch items: %#v", received.Items)
	}
}
