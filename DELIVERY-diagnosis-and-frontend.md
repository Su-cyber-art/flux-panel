# flux-panel 优化交付说明

本次改动包含两大块：**① 隧道/转发诊断逻辑的真实性重构（后端 Java + go-gost 节点端）**，**② 前端全面换框架重写（新增 `vue-frontend/`，Vue 3 + Naive UI）**。

---

## 一、诊断真实性重构

### 旧实现存在的“假象”（已全部修复）

| 问题 | 位置 | 后果 |
|---|---|---|
| 节点返回无 data 时判为“成功、0ms” | ForwardServiceImpl / TunnelServiceImpl | 断链被显示为完美 0ms 链路 |
| 解析异常也判为“成功” | 同上 | 数据异常被吞成绿色 |
| 硬编码 `www.google.com:443` 外网探测 | TunnelServiceImpl.diagnoseTunnel | 国内节点/出口封锁一律误报失败 |
| 逐跳缺端口时回落到 SSH 22 端口 | 同上 | 把“SSH 可达”当成“隧道可达” |
| 仅做 TCP 建连，不校验数据面 | TcpPing | 端口在监听但转发链断了仍显示绿色 |
| 入口服务是否在监听从不检测 | diagnoseForward | 入口服务未装成功也显示健康 |

### 新方案（真实、可信、部署安全）

诊断分为四类真实检查（前端按类别分组展示）：

1. **入口监听探测（LISTENER）**：入口节点向 `127.0.0.1:inPort` 真实建连，确认入口 gost 服务确实在监听。
2. **逐跳真实建连 + 延迟（HOP）**：每一跳由所在节点真实发起 TCP 建连到下一跳，返回**真实的 最小/平均/最大 延迟、抖动、丢包**，成功仅当至少一次握手成功（不再伪造）。
3. **目标可达（TARGET）**：出口/入口节点对最终目标地址真实建连。
4. **端到端数据回环（LOOPBACK）**——核心新增能力：
   - 出口节点启动一个带 token 校验的**一次性 echo 服务**（临时端口，自动过期）；
   - 每个中转节点启动一次性 **TCP 透传**，逐跳使用独立 token 鉴权并串联出与生产链路一致的节点拓扑；
   - 入口节点发起真实数据往返，**逐字节校验完整性**并测量 RTT（min/avg/max/jitter）；
   - 用完即拆除，全过程**不修改节点 gost 配置**、临时监听均带兜底自动过期，面板异常退出也不会遗留。

> 端口转发型隧道（单节点无中转）执行“入口节点数据面自检”（本机 echo 回环）；隧道级诊断在无活动转发时也能仅凭 overlay 完成整条链路的数据回环校验。

### 涉及文件

**go-gost 节点端**
- `go-gost/x/socket/diagnosis.go`（新增）：EchoServer / EchoRelay / EchoProbe / StopDiag 命令 + 增强版 TCP 建连统计（min/max/jitter）。
- `go-gost/x/socket/websocket_reporter.go`（修改）：`TcpPingResponse` 增加 min/max/jitter/次数字段；`handleTcpPing` 改用真实统计；`routeCommand` 注册 4 个新命令；移除旧 `tcpPingHost`。

**后端 Java**
- `common/dto/DiagnosisResultDto.java`（新增）：富指标诊断结果（含回环完整性/校验字节/轮次）。
- `common/utils/NodeDiagnostics.java`（新增）：诊断命令下发与解析、逐跳探测、链路 overlay 回环编排（含 finally 兜底拆除）。
- `service/impl/ForwardServiceImpl.java` / `TunnelServiceImpl.java`（修改）：`diagnoseForward` / `diagnoseTunnel` 重写为上述四类真实检查；移除硬编码与假成功分支；报告新增 `summary` 汇总。
- 测试：`TunnelServiceImplTest`（更新，校验探测预算在命令响应窗口内）、`NodeDiagnosticsTest`（新增，验证“离线节点必判失败、绝不伪造成功”等不变量）、`diagnosis_test.go`（新增，验证逐跳 token、真实回环和 TCP 建连统计）。

### 兼容性
- 面板↔节点协议保持不变（同一 WebSocket + AES-GCM 加密信道），新命令为增量；新版面板需配合更新后的节点端二进制才能使用“数据回环”，逐跳/监听探测对旧节点也可用。
- 诊断响应结构在旧字段基础上做**超集扩展**，旧前端仍可渲染。

---

## 二、前端换框架重写（`vue-frontend/`）

技术栈从 **React 18 + HeroUI + wouter** 换为 **Vue 3 + Vite + TypeScript + Naive UI + Pinia + vue-router + ECharts**。

- 完整移植 11 个页面：登录 / 强制改密 / 仪表盘 / 转发管理 / 隧道管理 / 节点监控 / 用户管理 / 限速管理 / 网站配置 / 我的 / 面板设置。
- 桌面端侧边栏布局、移动端底部标签栏布局自动切换；统一品牌设计系统、卡片悬浮与路由过渡动效；暗色模式跟随系统并支持顶栏一键切换。
- 鉴权、响应信封、验证码（tianai-captcha 逐字沿用）、节点实时遥测 WebSocket、WebView 原生桥全部对齐原实现。
- 部署方式不变：`Dockerfile` + `nginx.conf` 对外行为与原 `vite-frontend` 一致（`/api/v1`、`/flow`、`/system-info` 代理，SPA history 回退）。compose 里把前端目录换成 `vue-frontend` 即可。
- 诊断弹窗 `DiagnosisDialog` 按上文四类别可视化真实延迟/抖动/丢包与字节级完整性。

详见 `vue-frontend/README.md`。

---

## 三、如何构建与验证（需在可访问 npm/Maven/Go 代理的机器上执行）

> 本次改动在受限沙箱内完成，沙箱**禁用了 npm / Maven Central / Go 代理**，因此无法在此联网安装依赖与构建。代码已通过：Go 诊断代码的独立标准库编译 + `go vet`；Java 诊断逻辑的桩编译（类型校验）；以及全量的静态一致性检查（导入解析、组件/接口对齐、SFC 结构）。请在本地按下述步骤最终构建：

```bash
# 前端
cd vue-frontend && npm ci && npm run build           # 产物 dist/
# 后端
cd springboot-backend && mvn test                    # 运行含诊断的单测
# 节点端（两模块）
cd go-gost && go test ./... && cd x && go test ./...
```

---

## 四、未改动 / 需注意
- 未改动数据库结构、安装脚本、compose 端口与环境变量。
- 若要在生产启用“数据回环”诊断，请将所有节点更新到包含新节点端命令的版本。
- 原 `vite-frontend/` 已由 Vue 3 实现替换，CI、发布流程与 compose 均指向 `vue-frontend/`。
