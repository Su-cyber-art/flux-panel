package service

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/go-gost/core/observer/stats"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/internal/util/crypto"
	"github.com/go-gost/x/registry"
)

var httpReportURL string
var configReportURL string
var httpAESCrypto *crypto.AESCrypto // 新增：HTTP上报加密器
var reportHTTPClient = &http.Client{Timeout: 10 * time.Second}

const trafficReportPeriod = 5 * time.Second

// TrafficReportItem 流量报告项（压缩格式）
type TrafficReportItem struct {
	N string `json:"n"` // 服务名（name缩写）
	G int64  `json:"g"` // 服务创建代次（generation缩写）
	U int64  `json:"u"` // 上行流量（up缩写）
	D int64  `json:"d"` // 下行流量（down缩写）
}

// TrafficReportBatch carries absolute counters for one node process.
// A batch is retried unchanged until the server acknowledges its sequence.
type TrafficReportBatch struct {
	InstanceID string              `json:"instanceId"`
	StartedAt  int64               `json:"startedAt"`
	Sequence   uint64              `json:"sequence"`
	Items      []TrafficReportItem `json:"items"`
}

func SetHTTPReportURL(addr string, secret string) {
	httpReportURL = "http://" + addr + "/flow/upload/batch?secret=" + secret
	configReportURL = "http://" + addr + "/flow/config?secret=" + secret

	// 创建 AES 加密器
	var err error
	httpAESCrypto, err = crypto.NewAESCrypto(secret)
	if err != nil {
		fmt.Printf("❌ 创建 HTTP AES 加密器失败: %v\n", err)
		httpAESCrypto = nil
	} else {
		fmt.Printf("🔐 HTTP AES 加密器创建成功\n")
	}
}

// StartTrafficReporter starts one reporter for the whole node instead of one
// reporter per service.
func StartTrafficReporter(ctx context.Context) {
	if httpReportURL == "" {
		fmt.Printf("⚠️ 流量上报URL未设置，跳过流量上报\n")
		return
	}

	instanceID, err := newReporterInstanceID()
	if err != nil {
		fmt.Printf("❌ 创建流量上报实例ID失败: %v\n", err)
		return
	}

	startedAt := time.Now().UnixMilli()
	sequence := uint64(1)
	acknowledged := make(map[string]TrafficReportItem)
	var pending *TrafficReportBatch
	ticker := time.NewTicker(trafficReportPeriod)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			if pending == nil {
				items := collectTrafficReportItems(acknowledged)
				if len(items) == 0 {
					continue
				}
				pending = &TrafficReportBatch{
					InstanceID: instanceID,
					StartedAt:  startedAt,
					Sequence:   sequence,
					Items:      items,
				}
			}

			success, err := sendTrafficReport(ctx, *pending)
			if err != nil {
				fmt.Printf("发送批量流量报告失败: %v\n", err)
				continue
			}
			if success {
				for _, item := range pending.Items {
					acknowledged[item.N] = item
				}
				pending = nil
				sequence++
			}

		case <-ctx.Done():
			return
		}
	}
}

func collectTrafficReportItems(
	acknowledged map[string]TrafficReportItem,
) []TrafficReportItem {
	items := make([]TrafficReportItem, 0)
	for name, svc := range registry.ServiceRegistry().GetAll() {
		ss, ok := svc.(serviceStatus)
		if !ok || ss == nil || name == "web_api" || strings.HasSuffix(name, "_tls") {
			continue
		}
		status := ss.Status()
		if status == nil || status.Stats() == nil {
			continue
		}
		st := status.Stats()
		inputBytes := st.Get(stats.KindInputBytes)
		outputBytes := st.Get(stats.KindOutputBytes)
		if inputBytes == 0 && outputBytes == 0 {
			continue
		}
		item := TrafficReportItem{
			N: name,
			G: status.CreateTime().UnixNano(),
			U: uint64ToInt64(outputBytes),
			D: uint64ToInt64(inputBytes),
		}
		if previous, ok := acknowledged[name]; ok && previous == item {
			continue
		}
		items = append(items, item)
	}
	sort.Slice(items, func(i, j int) bool {
		return items[i].N < items[j].N
	})
	return items
}

func uint64ToInt64(value uint64) int64 {
	if value > math.MaxInt64 {
		return math.MaxInt64
	}
	return int64(value)
}

func newReporterInstanceID() (string, error) {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return hex.EncodeToString(value), nil
}

// sendTrafficReport sends one idempotent batch to the HTTP endpoint.
func sendTrafficReport(ctx context.Context, report TrafficReportBatch) (bool, error) {
	jsonData, err := json.Marshal(report)
	if err != nil {
		return false, fmt.Errorf("序列化报告数据失败: %v", err)
	}

	var requestBody []byte

	// 如果有加密器，则加密数据
	if httpAESCrypto != nil {
		encryptedData, err := httpAESCrypto.Encrypt(jsonData)
		if err != nil {
			fmt.Printf("⚠️ 加密流量报告失败，发送原始数据: %v\n", err)
			requestBody = jsonData
		} else {
			// 创建加密消息包装器
			encryptedMessage := map[string]interface{}{
				"encrypted": true,
				"data":      encryptedData,
				"timestamp": time.Now().Unix(),
			}
			requestBody, err = json.Marshal(encryptedMessage)
			if err != nil {
				fmt.Printf("⚠️ 序列化加密流量报告失败，发送原始数据: %v\n", err)
				requestBody = jsonData
			}
		}
	} else {
		requestBody = jsonData
	}

	req, err := http.NewRequestWithContext(ctx, "POST", httpReportURL, bytes.NewBuffer(requestBody))
	if err != nil {
		return false, fmt.Errorf("创建HTTP请求失败: %v", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "GOST-Traffic-Reporter/2.0")

	resp, err := reportHTTPClient.Do(req)
	if err != nil {
		return false, fmt.Errorf("发送HTTP请求失败: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP响应错误: %d %s", resp.StatusCode, resp.Status)
	}

	// 读取响应内容
	var responseBytes bytes.Buffer
	_, err = responseBytes.ReadFrom(resp.Body)
	if err != nil {
		return false, fmt.Errorf("读取响应内容失败: %v", err)
	}

	responseText := strings.TrimSpace(responseBytes.String())

	// 检查响应是否为"ok"
	if responseText == "ok" {
		return true, nil
	} else {
		return false, fmt.Errorf("服务器响应: %s (期望: ok)", responseText)
	}
}

// sendConfigReport 发送配置报告到HTTP接口
func sendConfigReport(ctx context.Context) (bool, error) {
	if configReportURL == "" {
		return false, fmt.Errorf("配置上报URL未设置")
	}

	// 获取配置数据
	configData, err := getConfigData()
	if err != nil {
		return false, fmt.Errorf("获取配置数据失败: %v", err)
	}

	var requestBody []byte

	// 如果有加密器，则加密数据
	if httpAESCrypto != nil {
		encryptedData, err := httpAESCrypto.Encrypt(configData)
		if err != nil {
			fmt.Printf("⚠️ 加密配置报告失败，发送原始数据: %v\n", err)
			requestBody = configData
		} else {
			// 创建加密消息包装器
			encryptedMessage := map[string]interface{}{
				"encrypted": true,
				"data":      encryptedData,
				"timestamp": time.Now().Unix(),
			}
			requestBody, err = json.Marshal(encryptedMessage)
			if err != nil {
				fmt.Printf("⚠️ 序列化加密配置报告失败，发送原始数据: %v\n", err)
				requestBody = configData
			}
		}
	} else {
		requestBody = configData
	}

	req, err := http.NewRequestWithContext(ctx, "POST", configReportURL, bytes.NewBuffer(requestBody))
	if err != nil {
		return false, fmt.Errorf("创建HTTP请求失败: %v", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Config-Reporter/1.0")

	resp, err := reportHTTPClient.Do(req)
	if err != nil {
		return false, fmt.Errorf("发送HTTP请求失败: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP响应错误: %d %s", resp.StatusCode, resp.Status)
	}

	// 读取响应内容
	var responseBytes bytes.Buffer
	_, err = responseBytes.ReadFrom(resp.Body)
	if err != nil {
		return false, fmt.Errorf("读取响应内容失败: %v", err)
	}

	responseText := strings.TrimSpace(responseBytes.String())

	// 检查响应是否为"ok"
	if responseText == "ok" {
		return true, nil
	} else {
		return false, fmt.Errorf("服务器响应: %s (期望: ok)", responseText)
	}
}

// StartConfigReporter 启动配置定时上报器（每10分钟上报一次）
func StartConfigReporter(ctx context.Context) {
	if configReportURL == "" {
		fmt.Printf("⚠️ 配置上报URL未设置，跳过定时上报\n")
		return
	}

	fmt.Printf("🚀 配置定时上报器已启动，每10分钟上报一次（WebSocket连接稳定后启动）\n")

	// 创建10分钟定时器
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	// 立即执行一次配置上报
	go func() {
		success, err := sendConfigReport(ctx)
		if err != nil {
			fmt.Printf("❌ 初始配置上报失败: %v\n", err)
		} else if success {
			fmt.Printf("✅ 初始配置上报成功\n")
		}
	}()

	// 定时上报循环
	for {
		select {
		case <-ticker.C:
			go func() {
				success, err := sendConfigReport(ctx)
				if err != nil {
					fmt.Printf("❌ 定时配置上报失败: %v\n", err)
				} else if success {
					fmt.Printf("✅ 定时配置上报成功\n")
				}
			}()

		case <-ctx.Done():
			fmt.Printf("⏹️ 配置定时上报器已停止\n")
			return
		}
	}
}

// serviceStatus 接口定义
type serviceStatus interface {
	Status() *Status
}

// getConfigResponse 配置响应结构
type getConfigResponse struct {
	Config *config.Config `json:"config"`
}

// getConfigData 获取配置数据（避免循环依赖）
func getConfigData() ([]byte, error) {
	config.OnUpdate(func(c *config.Config) error {
		for _, svc := range c.Services {
			if svc == nil {
				continue
			}
			s := registry.ServiceRegistry().Get(svc.Name)
			ss, ok := s.(serviceStatus)
			if ok && ss != nil {
				status := ss.Status()
				svc.Status = &config.ServiceStatus{
					CreateTime: status.CreateTime().Unix(),
					State:      string(status.State()),
				}
				if st := status.Stats(); st != nil {
					svc.Status.Stats = &config.ServiceStats{
						TotalConns:   st.Get(stats.KindTotalConns),
						CurrentConns: st.Get(stats.KindCurrentConns),
						TotalErrs:    st.Get(stats.KindTotalErrs),
						InputBytes:   st.Get(stats.KindInputBytes),
						OutputBytes:  st.Get(stats.KindOutputBytes),
					}
				}
				for _, ev := range status.Events() {
					if !ev.Time.IsZero() {
						svc.Status.Events = append(svc.Status.Events, config.ServiceEvent{
							Time: ev.Time.Unix(),
							Msg:  ev.Message,
						})
					}
				}
			}
		}
		return nil
	})

	var resp getConfigResponse
	resp.Config = config.Global()

	buf := &bytes.Buffer{}
	resp.Config.Write(buf, "json")
	return buf.Bytes(), nil
}
