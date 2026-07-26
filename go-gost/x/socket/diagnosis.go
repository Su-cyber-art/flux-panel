package socket

import (
	"bytes"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net"
	"sync"
	"time"
)

// 诊断相关的命令实现。
//
// 设计目标：为面板提供“真实”的链路诊断能力，且不污染节点的 gost 运行配置。
//   - TcpPing         : 真实 TCP 建连探测，返回 min/avg/max/jitter/丢包等真实指标
//   - EchoServer      : 在节点上启动一次性、带 token 校验的 TCP echo 监听（自动过期）
//   - EchoRelay       : 在中转节点上启动一次性、逐跳 token 校验的 TCP 透传，用于串联真实链路
//   - EchoProbe       : 从入口节点发起真实数据往返，逐轮发送随机数据并按字节校验完整性
//   - StopDiag        : 主动关闭指定 token 的诊断监听
//
// 所有监听均绑定临时端口（:0），仅存在于内存中，不会写入 gost.json，并且都带有
// 兜底的自动过期定时器，即使面板异常退出也不会遗留监听。

const (
	diagMaxListeners      = 32
	diagDefaultDurationMs = 20000
	diagMaxDurationMs     = 120000
	diagTokenBytes        = 16 // -> 32 hex 字符
	diagMaxRounds         = 20
	diagMaxPayload        = 65536
)

// ---------- 请求/响应结构 ----------

type EchoServerRequest struct {
	DurationMs int    `json:"durationMs"`
	RequestId  string `json:"requestId,omitempty"`
}

type EchoServerResponse struct {
	Port      int    `json:"port"`
	Token     string `json:"token"`
	RequestId string `json:"requestId,omitempty"`
}

type EchoRelayRequest struct {
	NextHop    string `json:"nextHop"`
	NextToken  string `json:"nextToken"`
	DurationMs int    `json:"durationMs"`
	RequestId  string `json:"requestId,omitempty"`
}

type EchoRelayResponse struct {
	Port      int    `json:"port"`
	Token     string `json:"token"`
	RequestId string `json:"requestId,omitempty"`
}

type StopDiagRequest struct {
	Token     string `json:"token"`
	RequestId string `json:"requestId,omitempty"`
}

type EchoProbeRequest struct {
	Target      string `json:"target"`
	Token       string `json:"token"`
	Rounds      int    `json:"rounds"`
	PayloadSize int    `json:"payloadSize"`
	TimeoutMs   int    `json:"timeoutMs"`
	RequestId   string `json:"requestId,omitempty"`
}

type EchoProbeResponse struct {
	Target        string  `json:"target"`
	Success       bool    `json:"success"`
	Rounds        int     `json:"rounds"`
	OkRounds      int     `json:"okRounds"`
	BytesVerified int64   `json:"bytesVerified"`
	IntegrityOk   bool    `json:"integrityOk"`
	AverageTime   float64 `json:"averageTime"`
	MinTime       float64 `json:"minTime"`
	MaxTime       float64 `json:"maxTime"`
	Jitter        float64 `json:"jitter"`
	PacketLoss    float64 `json:"packetLoss"`
	ErrorMessage  string  `json:"errorMessage,omitempty"`
	RequestId     string  `json:"requestId,omitempty"`
}

// ---------- 诊断监听管理 ----------

type diagListener struct {
	ln    net.Listener
	timer *time.Timer
}

var (
	diagMu        sync.Mutex
	diagListeners = map[string]*diagListener{}
)

func newDiagToken() (string, error) {
	b := make([]byte, diagTokenBytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func normalizeDurationMs(v int) int {
	if v <= 0 {
		return diagDefaultDurationMs
	}
	if v > diagMaxDurationMs {
		return diagMaxDurationMs
	}
	return v
}

func diagRegister(token string, dl *diagListener, durationMs int) error {
	diagMu.Lock()
	defer diagMu.Unlock()
	if len(diagListeners) >= diagMaxListeners {
		return fmt.Errorf("诊断监听数量已达上限")
	}
	if _, exists := diagListeners[token]; exists {
		return fmt.Errorf("诊断token冲突")
	}
	diagListeners[token] = dl
	dl.timer = time.AfterFunc(time.Duration(durationMs)*time.Millisecond, func() { diagStop(token) })
	return nil
}

func diagStop(token string) {
	diagMu.Lock()
	dl := diagListeners[token]
	delete(diagListeners, token)
	diagMu.Unlock()
	if dl == nil {
		return
	}
	if dl.timer != nil {
		dl.timer.Stop()
	}
	if dl.ln != nil {
		_ = dl.ln.Close()
	}
}

func remarshal(data interface{}, v interface{}) error {
	b, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

// ---------- EchoServer ----------

func (w *WebSocketReporter) handleEchoServer(data interface{}) (EchoServerResponse, error) {
	var req EchoServerRequest
	if err := remarshal(data, &req); err != nil {
		return EchoServerResponse{}, fmt.Errorf("解析回环服务请求失败: %v", err)
	}
	token, err := newDiagToken()
	if err != nil {
		return EchoServerResponse{}, fmt.Errorf("生成token失败: %v", err)
	}
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		return EchoServerResponse{}, fmt.Errorf("启动回环监听失败: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	dl := &diagListener{ln: ln}
	if err := diagRegister(token, dl, normalizeDurationMs(req.DurationMs)); err != nil {
		_ = ln.Close()
		return EchoServerResponse{}, err
	}

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go serveEcho(conn, token)
		}
	}()

	fmt.Printf("启动诊断回环服务: 端口=%d\n", port)
	return EchoServerResponse{Port: port, Token: token, RequestId: req.RequestId}, nil
}

// serveEcho 校验 token 后进入纯字节 echo。
func serveEcho(conn net.Conn, token string) {
	defer conn.Close()
	handshake := make([]byte, len(token))
	_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	if _, err := io.ReadFull(conn, handshake); err != nil {
		return
	}
	if subtle.ConstantTimeCompare(handshake, []byte(token)) != 1 {
		return
	}
	buf := make([]byte, 32*1024)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, err := conn.Read(buf)
		if n > 0 {
			if werr := writeFull(conn, buf[:n]); werr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

// ---------- EchoRelay ----------

func (w *WebSocketReporter) handleEchoRelay(data interface{}) (EchoRelayResponse, error) {
	var req EchoRelayRequest
	if err := remarshal(data, &req); err != nil {
		return EchoRelayResponse{}, fmt.Errorf("解析中转请求失败: %v", err)
	}
	if req.NextHop == "" {
		return EchoRelayResponse{}, fmt.Errorf("缺少下一跳地址")
	}
	if req.NextToken == "" {
		return EchoRelayResponse{}, fmt.Errorf("缺少下一跳token")
	}
	token, err := newDiagToken()
	if err != nil {
		return EchoRelayResponse{}, fmt.Errorf("生成token失败: %v", err)
	}
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		return EchoRelayResponse{}, fmt.Errorf("启动中转监听失败: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	dl := &diagListener{ln: ln}
	if err := diagRegister(token, dl, normalizeDurationMs(req.DurationMs)); err != nil {
		_ = ln.Close()
		return EchoRelayResponse{}, err
	}

	nextHop := req.NextHop
	nextToken := req.NextToken
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go relayPipe(conn, nextHop, token, nextToken)
		}
	}()

	fmt.Printf("启动诊断中转: 端口=%d -> %s\n", port, nextHop)
	return EchoRelayResponse{Port: port, Token: token, RequestId: req.RequestId}, nil
}

// relayPipe 校验本跳 token，连接下一跳后代为发送下一跳 token，再进入双向透传。
// 这样每个临时监听都无法被未授权连接当作开放代理使用。
func relayPipe(client net.Conn, nextHop, token, nextToken string) {
	defer client.Close()

	handshake := make([]byte, len(token))
	_ = client.SetReadDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.ReadFull(client, handshake); err != nil {
		return
	}
	if subtle.ConstantTimeCompare(handshake, []byte(token)) != 1 {
		return
	}
	_ = client.SetReadDeadline(time.Time{})

	upstream, err := net.DialTimeout("tcp", nextHop, 10*time.Second)
	if err != nil {
		return
	}
	defer upstream.Close()
	_ = upstream.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := writeFull(upstream, []byte(nextToken)); err != nil {
		return
	}
	_ = upstream.SetWriteDeadline(time.Time{})

	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(upstream, client); done <- struct{}{} }()
	go func() { _, _ = io.Copy(client, upstream); done <- struct{}{} }()
	<-done
}

// ---------- StopDiag ----------

func (w *WebSocketReporter) handleStopDiag(data interface{}) error {
	var req StopDiagRequest
	if err := remarshal(data, &req); err != nil {
		return fmt.Errorf("解析停止请求失败: %v", err)
	}
	if req.Token == "" {
		return fmt.Errorf("缺少token")
	}
	diagStop(req.Token)
	return nil
}

// ---------- EchoProbe ----------

func (w *WebSocketReporter) handleEchoProbe(data interface{}) (EchoProbeResponse, error) {
	var req EchoProbeRequest
	if err := remarshal(data, &req); err != nil {
		return EchoProbeResponse{}, fmt.Errorf("解析回环探测请求失败: %v", err)
	}
	resp := EchoProbeResponse{Target: req.Target, RequestId: req.RequestId, PacketLoss: 100}
	if req.Target == "" || req.Token == "" {
		resp.ErrorMessage = "缺少目标地址或token"
		return resp, nil
	}

	rounds := req.Rounds
	if rounds <= 0 {
		rounds = 3
	}
	if rounds > diagMaxRounds {
		rounds = diagMaxRounds
	}
	size := req.PayloadSize
	if size <= 0 {
		size = 2048
	}
	if size > diagMaxPayload {
		size = diagMaxPayload
	}
	timeout := time.Duration(req.TimeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 8 * time.Second
	}

	conn, err := net.DialTimeout("tcp", req.Target, timeout)
	if err != nil {
		resp.ErrorMessage = fmt.Sprintf("连接目标失败: %v", err)
		return resp, nil
	}
	defer conn.Close()

	_ = conn.SetWriteDeadline(time.Now().Add(timeout))
	if err := writeFull(conn, []byte(req.Token)); err != nil {
		resp.ErrorMessage = fmt.Sprintf("发送握手失败: %v", err)
		return resp, nil
	}

	resp.Rounds = rounds
	integrity := true
	payload := make([]byte, size)
	readBuf := make([]byte, size)
	var times []float64

	for i := 0; i < rounds; i++ {
		if _, err := rand.Read(payload); err != nil {
			resp.ErrorMessage = fmt.Sprintf("生成随机数据失败: %v", err)
			break
		}
		start := time.Now()
		_ = conn.SetWriteDeadline(time.Now().Add(timeout))
		if err := writeFull(conn, payload); err != nil {
			resp.ErrorMessage = fmt.Sprintf("第%d轮写入失败: %v", i+1, err)
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(timeout))
		if _, err := io.ReadFull(conn, readBuf); err != nil {
			resp.ErrorMessage = fmt.Sprintf("第%d轮读取失败: %v", i+1, err)
			break
		}
		rtt := float64(time.Since(start).Microseconds()) / 1000.0
		if !bytes.Equal(payload, readBuf) {
			integrity = false
			resp.ErrorMessage = fmt.Sprintf("第%d轮数据完整性校验失败", i+1)
			continue
		}
		resp.OkRounds++
		resp.BytesVerified += int64(size)
		times = append(times, rtt)
	}

	if len(times) > 0 {
		minV, maxV, sum := times[0], times[0], 0.0
		for _, t := range times {
			if t < minV {
				minV = t
			}
			if t > maxV {
				maxV = t
			}
			sum += t
		}
		avg := sum / float64(len(times))
		var variance float64
		for _, t := range times {
			variance += (t - avg) * (t - avg)
		}
		resp.AverageTime = round2(avg)
		resp.MinTime = round2(minV)
		resp.MaxTime = round2(maxV)
		resp.Jitter = round2(math.Sqrt(variance / float64(len(times))))
	}
	resp.PacketLoss = round2(float64(rounds-resp.OkRounds) / float64(rounds) * 100)
	resp.IntegrityOk = integrity && resp.OkRounds == rounds
	resp.Success = resp.OkRounds == rounds && integrity
	if resp.Success {
		resp.ErrorMessage = ""
	}
	return resp, nil
}

func writeFull(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}

// ---------- 增强版 TCP 建连统计 ----------

type tcpPingStat struct {
	avg      float64
	min      float64
	max      float64
	jitter   float64
	loss     float64
	attempts int
	success  int
	err      error
}

// tcpPingCollect 发起 count 次真实 TCP 建连，返回 min/avg/max/jitter/丢包 等真实指标。
func tcpPingCollect(ip string, port int, count int, timeoutMs int) tcpPingStat {
	stat := tcpPingStat{loss: 100, attempts: count}
	timeout := time.Duration(timeoutMs) * time.Millisecond
	target := net.JoinHostPort(ip, fmt.Sprintf("%d", port))

	// 域名先解析一次，避免把 DNS 时间累加进建连延迟。
	if net.ParseIP(ip) == nil {
		addrs, err := net.LookupHost(ip)
		if err != nil {
			stat.err = fmt.Errorf("DNS解析失败: %v", err)
			return stat
		}
		if len(addrs) == 0 {
			stat.err = fmt.Errorf("DNS解析未返回IP")
			return stat
		}
		target = net.JoinHostPort(addrs[0], fmt.Sprintf("%d", port))
	}

	var times []float64
	for i := 0; i < count; i++ {
		start := time.Now()
		conn, err := net.DialTimeout("tcp", target, timeout)
		elapsed := float64(time.Since(start).Microseconds()) / 1000.0
		if err == nil {
			_ = conn.Close()
			times = append(times, elapsed)
			stat.success++
		}
		if i < count-1 {
			time.Sleep(100 * time.Millisecond)
		}
	}

	if stat.success == 0 {
		stat.err = fmt.Errorf("所有TCP建连尝试均失败")
		return stat
	}

	minV, maxV, sum := times[0], times[0], 0.0
	for _, t := range times {
		if t < minV {
			minV = t
		}
		if t > maxV {
			maxV = t
		}
		sum += t
	}
	avg := sum / float64(len(times))
	var variance float64
	for _, t := range times {
		variance += (t - avg) * (t - avg)
	}
	stat.avg = round2(avg)
	stat.min = round2(minV)
	stat.max = round2(maxV)
	stat.jitter = round2(math.Sqrt(variance / float64(len(times))))
	stat.loss = round2(float64(count-stat.success) / float64(count) * 100)
	return stat
}

func round2(v float64) float64 {
	return math.Round(v*100) / 100
}
