package socket

import (
	"bytes"
	"crypto/rand"
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
//   - EchoRelay       : 在中转节点上启动一次性 raw TCP 透传（自动过期），用于逐跳串联出真实链路
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
	// diagMaxBudget 单条探测命令的绝对上限，必须小于面板的命令响应窗口(10s)
	diagMaxBudget = 9 * time.Second
)

// ---------- 请求/响应结构 ----------

type EchoServerRequest struct {
	DurationMs int `json:"durationMs"`
	// PortStart/PortEnd：面板下发的、该节点防火墙已放通的端口区间。
	// 临时监听必须落在该区间内，否则随机高位端口会被防火墙丢包，导致回环探测必然失败。
	PortStart int    `json:"portStart"`
	PortEnd   int    `json:"portEnd"`
	RequestId string `json:"requestId,omitempty"`
}

type EchoServerResponse struct {
	Port      int    `json:"port"`
	Token     string `json:"token"`
	RequestId string `json:"requestId,omitempty"`
}

type EchoRelayRequest struct {
	NextHop    string `json:"nextHop"`
	DurationMs int    `json:"durationMs"`
	PortStart  int    `json:"portStart"`
	PortEnd    int    `json:"portEnd"`
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
	// TimeoutMs 为单次操作超时（兼容旧面板）；BudgetMs 为整个探测的硬总预算，
	// 优先生效。必须小于面板的命令响应窗口，否则面板会先放弃而节点仍在跑。
	TimeoutMs int    `json:"timeoutMs"`
	BudgetMs  int    `json:"budgetMs"`
	RequestId string `json:"requestId,omitempty"`
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

func diagRegister(token string, dl *diagListener, durationMs int) {
	diagMu.Lock()
	defer diagMu.Unlock()
	diagListeners[token] = dl
	dl.timer = time.AfterFunc(time.Duration(durationMs)*time.Millisecond, func() { diagStop(token) })
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

func diagCount() int {
	diagMu.Lock()
	defer diagMu.Unlock()
	return len(diagListeners)
}

// listenInRange 在面板放通的端口区间内寻找一个可用端口监听。
// 随机起点 + 有限次尝试，避免与生产转发端口频繁碰撞；区间不合法或全被占用时
// 回退到 :0（仅适用于无防火墙限制的环境）。
func listenInRange(portStart, portEnd int) (net.Listener, error) {
	if portStart > 0 && portEnd >= portStart && portEnd <= 65535 {
		span := portEnd - portStart + 1
		attempts := span
		if attempts > 40 {
			attempts = 40
		}
		offset := 0
		if span > 1 {
			if b := make([]byte, 2); true {
				if _, err := rand.Read(b); err == nil {
					offset = (int(b[0])<<8 | int(b[1])) % span
				}
			}
		}
		for i := 0; i < attempts; i++ {
			port := portStart + (offset+i)%span
			ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
			if err == nil {
				return ln, nil
			}
		}
		return nil, fmt.Errorf("端口区间 %d-%d 内无可用端口", portStart, portEnd)
	}
	return net.Listen("tcp", ":0")
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
	if diagCount() >= diagMaxListeners {
		return EchoServerResponse{}, fmt.Errorf("诊断监听数量已达上限")
	}
	token, err := newDiagToken()
	if err != nil {
		return EchoServerResponse{}, fmt.Errorf("生成token失败: %v", err)
	}
	ln, err := listenInRange(req.PortStart, req.PortEnd)
	if err != nil {
		return EchoServerResponse{}, fmt.Errorf("启动回环监听失败: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	dl := &diagListener{ln: ln}
	diagRegister(token, dl, normalizeDurationMs(req.DurationMs))

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go serveEcho(conn, token)
		}
	}()

	fmt.Printf("🧪 启动诊断回环服务: 端口=%d token=%s\n", port, token)
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
	if string(handshake) != token {
		return
	}
	buf := make([]byte, 32*1024)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, err := conn.Read(buf)
		if n > 0 {
			if _, werr := conn.Write(buf[:n]); werr != nil {
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
	if diagCount() >= diagMaxListeners {
		return EchoRelayResponse{}, fmt.Errorf("诊断监听数量已达上限")
	}
	token, err := newDiagToken()
	if err != nil {
		return EchoRelayResponse{}, fmt.Errorf("生成token失败: %v", err)
	}
	ln, err := listenInRange(req.PortStart, req.PortEnd)
	if err != nil {
		return EchoRelayResponse{}, fmt.Errorf("启动中转监听失败: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	dl := &diagListener{ln: ln}
	diagRegister(token, dl, normalizeDurationMs(req.DurationMs))

	nextHop := req.NextHop
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go relayPipe(conn, nextHop)
		}
	}()

	fmt.Printf("🧪 启动诊断中转: 端口=%d -> %s\n", port, nextHop)
	return EchoRelayResponse{Port: port, Token: token, RequestId: req.RequestId}, nil
}

func relayPipe(client net.Conn, nextHop string) {
	defer client.Close()
	upstream, err := net.DialTimeout("tcp", nextHop, 10*time.Second)
	if err != nil {
		return
	}
	defer upstream.Close()
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
	// 硬总预算：整个探测（含建连、握手、所有轮次）必须在 budget 内返回，
	// 否则面板会先于节点放弃，前端看到的就是"总是超时"。
	budget := time.Duration(req.BudgetMs) * time.Millisecond
	if budget <= 0 {
		// 兼容旧面板：把单次超时折算成总预算，而不是逐操作各用一份
		budget = time.Duration(req.TimeoutMs) * time.Millisecond
	}
	if budget <= 0 {
		budget = 7 * time.Second
	}
	if budget > diagMaxBudget {
		budget = diagMaxBudget
	}
	deadline := time.Now().Add(budget)
	remaining := func() time.Duration { return time.Until(deadline) }

	// 建连最多占用总预算的一半，给数据往返留出时间
	dialTimeout := budget / 2
	if dialTimeout < 500*time.Millisecond {
		dialTimeout = 500 * time.Millisecond
	}
	conn, err := net.DialTimeout("tcp", req.Target, dialTimeout)
	if err != nil {
		resp.ErrorMessage = fmt.Sprintf("连接目标失败: %v", err)
		return resp, nil
	}
	defer conn.Close()

	// 一次性给整条连接设总截止时间，后续读写都受它约束
	_ = conn.SetDeadline(deadline)

	if _, err := conn.Write([]byte(req.Token)); err != nil {
		resp.ErrorMessage = fmt.Sprintf("发送握手失败: %v", err)
		return resp, nil
	}

	integrity := true
	payload := make([]byte, size)
	readBuf := make([]byte, size)
	var times []float64
	attempted := 0

	for i := 0; i < rounds; i++ {
		// 预算不足则提前收尾，用已完成的轮次给出真实结论
		if remaining() <= 200*time.Millisecond {
			if attempted == 0 {
				resp.ErrorMessage = "探测预算不足，未能完成任何一轮数据往返"
			}
			break
		}
		attempted++
		if _, err := rand.Read(payload); err != nil {
			resp.ErrorMessage = fmt.Sprintf("生成随机数据失败: %v", err)
			break
		}
		start := time.Now()
		if _, err := conn.Write(payload); err != nil {
			resp.ErrorMessage = fmt.Sprintf("第%d轮写入失败: %v", i+1, err)
			break
		}
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

	// 丢包率按实际尝试的轮次计算，避免因预算收尾而虚报丢包
	if attempted == 0 {
		attempted = 1
	}
	resp.Rounds = attempted

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
	resp.PacketLoss = round2(float64(attempted-resp.OkRounds) / float64(attempted) * 100)
	resp.IntegrityOk = integrity && resp.OkRounds == attempted
	resp.Success = resp.OkRounds == attempted && integrity && resp.OkRounds > 0
	if resp.Success {
		resp.ErrorMessage = ""
	}
	return resp, nil
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
// budgetMs 为整个探测（含 DNS）的硬总预算，超出即停止并按已完成次数给出结论。
func tcpPingCollect(ip string, port int, count int, timeoutMs int, budgetMs int) tcpPingStat {
	stat := tcpPingStat{loss: 100, attempts: count}
	timeout := time.Duration(timeoutMs) * time.Millisecond

	budget := time.Duration(budgetMs) * time.Millisecond
	if budget <= 0 {
		// 兼容旧面板：按 count 次逐操作用时折算，并夹在绝对上限内
		budget = time.Duration(count)*timeout + time.Duration(count)*100*time.Millisecond
	}
	if budget > diagMaxBudget {
		budget = diagMaxBudget
	}
	deadline := time.Now().Add(budget)

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
	attempted := 0
	for i := 0; i < count; i++ {
		remaining := time.Until(deadline)
		if remaining <= 100*time.Millisecond {
			break // 预算耗尽，用已完成的次数得出结论
		}
		attempted++
		dialTimeout := timeout
		if dialTimeout > remaining {
			dialTimeout = remaining
		}
		start := time.Now()
		conn, err := net.DialTimeout("tcp", target, dialTimeout)
		elapsed := float64(time.Since(start).Microseconds()) / 1000.0
		if err == nil {
			_ = conn.Close()
			times = append(times, elapsed)
			stat.success++
		}
		if i < count-1 && time.Until(deadline) > 200*time.Millisecond {
			time.Sleep(100 * time.Millisecond)
		}
	}

	if attempted == 0 {
		attempted = 1
	}
	stat.attempts = attempted

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
	stat.loss = round2(float64(attempted-stat.success) / float64(attempted) * 100)
	return stat
}

func round2(v float64) float64 {
	return math.Round(v*100) / 100
}
