package com.admin.common.utils;

import com.admin.common.dto.DiagnosisResultDto;
import com.admin.common.dto.GostDto;
import com.admin.entity.Node;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 节点诊断客户端。
 *
 * <p>封装面板 → 节点 的诊断命令下发与结果解析，供转发/隧道诊断复用。</p>
 *
 * <p>核心原则：<b>绝不伪造成功</b>。节点若未返回真实数据，一律判为失败，
 * 从根源上杜绝旧实现中「无数据即 0ms 成功」「解析异常即成功」等假象。</p>
 */
@Slf4j
public final class NodeDiagnostics {

    private static final String OK = "OK";

    /**
     * 逐跳建连参数。
     * TCP_PING_BUDGET_MS 是节点侧的<b>硬总预算</b>（含 DNS 与全部重试），
     * 必须显著小于 {@link WebSocketServer#COMMAND_RESPONSE_TIMEOUT_MILLIS}，
     * 否则面板会先放弃而节点仍在跑，表现为"总是超时"。
     */
    public static final int TCP_PING_COUNT = 3;
    public static final int TCP_PING_TIMEOUT_MS = 1_200;
    public static final int TCP_PING_BUDGET_MS = 4_000;

    /** 端到端回环参数，ECHO_BUDGET_MS 同样是节点侧硬总预算 */
    public static final int ECHO_ROUNDS = 2;
    public static final int ECHO_PAYLOAD = 2_048;
    public static final int ECHO_BUDGET_MS = 7_000;
    /** 诊断监听兜底存活时间：必须覆盖整轮编排（含并行探测与回环）总时长 */
    public static final int ECHO_DURATION_MS = 30_000;

    /** 单次诊断请求的总时长上限：小于前端请求超时，保证能返回部分结果而不是超时 */
    public static final long TOTAL_BUDGET_MS = 20_000;

    /** 探测线程池：按源节点分组并行，缩短整体墙钟 */
    private static final ExecutorService DIAG_POOL = new ThreadPoolExecutor(
            0, 16, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "node-diagnostics");
                t.setDaemon(true);
                return t;
            });

    private NodeDiagnostics() {
    }

    /** 单次诊断的总时长预算 */
    public static final class Budget {
        private final long deadline;

        private Budget(long totalMs) {
            this.deadline = System.currentTimeMillis() + totalMs;
        }

        public static Budget of(long totalMs) {
            return new Budget(totalMs);
        }

        public static Budget standard() {
            return new Budget(TOTAL_BUDGET_MS);
        }

        public long remainingMs() {
            return deadline - System.currentTimeMillis();
        }

        /** 剩余时间不足以再跑一次探测时视为耗尽 */
        public boolean exhausted() {
            return remainingMs() <= TCP_PING_BUDGET_MS;
        }
    }

    /** 一条待执行的探测 */
    public static final class ProbeSpec {
        final Node node;
        final String host;
        final int port;
        final String description;
        final String category;

        public ProbeSpec(Node node, String host, int port, String description, String category) {
            this.node = node;
            this.host = host;
            this.port = port;
            this.description = description;
            this.category = category;
        }
    }

    /**
     * 批量执行探测：按<b>源节点</b>分组，不同节点并行、同一节点串行。
     *
     * <p>同节点串行是为了兼容尚未升级的旧节点（旧节点在读循环里同步处理命令，
     * 并发下发会排队并导致超时）。整体墙钟从"逐条相加"降到"最慢的那一组"。</p>
     */
    public static List<DiagnosisResultDto> probeBatch(List<ProbeSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return new ArrayList<>();
        }
        if (specs.size() == 1) {
            ProbeSpec s = specs.get(0);
            return new ArrayList<>(List.of(tcpProbe(s.node, s.host, s.port, s.description, s.category)));
        }

        // 保留调用方给定的顺序用于最终排序
        Map<ProbeSpec, Integer> order = new IdentityHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            order.put(specs.get(i), i);
        }
        Map<Long, List<ProbeSpec>> byNode = new LinkedHashMap<>();
        for (ProbeSpec s : specs) {
            byNode.computeIfAbsent(s.node.getId(), k -> new ArrayList<>()).add(s);
        }

        Map<Integer, DiagnosisResultDto> collected = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (List<ProbeSpec> group : byNode.values()) {
            futures.add(DIAG_POOL.submit(() -> {
                for (ProbeSpec s : group) {
                    collected.put(order.get(s), tcpProbe(s.node, s.host, s.port, s.description, s.category));
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get(WebSocketServer.COMMAND_RESPONSE_TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("并行探测未在预期内完成: {}", e.getMessage());
            }
        }

        List<DiagnosisResultDto> results = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            DiagnosisResultDto r = collected.get(i);
            ProbeSpec s = specs.get(i);
            results.add(r != null ? r
                    : DiagnosisResultDto.of(s.category, s.node.getId(), s.node.getName(), s.host, s.port,
                            s.description).fail("探测未在预期时间内返回"));
        }
        return results;
    }

    /** 生成"因总预算耗尽而跳过"的结果，让前端明确知道是被截断而不是失败 */
    public static DiagnosisResultDto skipped(String category, String description) {
        DiagnosisResultDto d = DiagnosisResultDto.of(category, null, null, null, null, description);
        return d.fail("诊断总时长已达上限，该项被跳过");
    }

    /** 诊断监听句柄（EchoServer / EchoRelay 返回） */
    public static final class DiagEndpoint {
        public final boolean ok;
        public final int port;
        public final String token;
        public final String error;

        private DiagEndpoint(boolean ok, int port, String token, String error) {
            this.ok = ok;
            this.port = port;
            this.token = token;
            this.error = error;
        }

        static DiagEndpoint ok(int port, String token) {
            return new DiagEndpoint(true, port, token, null);
        }

        static DiagEndpoint err(String error) {
            return new DiagEndpoint(false, 0, null, error);
        }
    }

    /**
     * 真实 TCP 建连探测（由 node 发起），返回真实的 min/avg/max/jitter/丢包。
     */
    public static DiagnosisResultDto tcpProbe(Node node, String host, int port,
                                              String description, String category) {
        DiagnosisResultDto r = DiagnosisResultDto.of(category, node.getId(), node.getName(),
                host, port, description);
        JSONObject payload = new JSONObject();
        payload.put("ip", host);
        payload.put("port", port);
        payload.put("count", TCP_PING_COUNT);
        payload.put("timeout", TCP_PING_TIMEOUT_MS);
        payload.put("budgetMs", TCP_PING_BUDGET_MS);

        GostDto resp;
        try {
            resp = WebSocketServer.send_msg(node.getId(), payload, "TcpPing");
        } catch (Exception e) {
            return r.fail("诊断命令下发异常: " + e.getMessage());
        }

        if (resp == null) {
            return r.fail("节点无响应");
        }
        if (!OK.equals(resp.getMsg())) {
            return r.fail(resp.getMsg() != null ? resp.getMsg() : "节点无响应");
        }
        // 关键：必须有结构化数据，否则判失败（杜绝假成功）
        if (!(resp.getData() instanceof JSONObject)) {
            return r.fail("节点未返回诊断数据");
        }
        JSONObject data = (JSONObject) resp.getData();
        boolean success = data.getBooleanValue("success");
        if (!success) {
            String err = data.getString("errorMessage");
            return r.fail(err != null && !err.isEmpty() ? err : "TCP建连失败");
        }
        r.setSuccess(true);
        r.setMessage("TCP建连成功");
        r.setAverageTime(data.getDoubleValue("averageTime"));
        r.setMinTime(data.containsKey("minTime") ? data.getDoubleValue("minTime") : data.getDoubleValue("averageTime"));
        r.setMaxTime(data.containsKey("maxTime") ? data.getDoubleValue("maxTime") : data.getDoubleValue("averageTime"));
        r.setJitter(data.containsKey("jitter") ? data.getDoubleValue("jitter") : 0.0);
        r.setPacketLoss(data.getDoubleValue("packetLoss"));
        return r;
    }

    /**
     * 在节点上启动一次性 echo 回环服务。
     *
     * <p>临时端口必须落在该节点<b>已放通</b>的端口区间内（portSta..portEnd），
     * 否则随机高位端口会被防火墙/安全组丢包，导致回环探测必然失败。</p>
     */
    public static DiagEndpoint startEcho(Node node) {
        JSONObject payload = new JSONObject();
        payload.put("durationMs", ECHO_DURATION_MS);
        putPortRange(payload, node);
        return parseEndpoint(node, payload, "EchoServer");
    }

    /** 在中转节点上启动一次性 raw TCP 透传，指向下一跳 */
    public static DiagEndpoint startRelay(Node node, String nextHop) {
        JSONObject payload = new JSONObject();
        payload.put("nextHop", nextHop);
        payload.put("durationMs", ECHO_DURATION_MS);
        putPortRange(payload, node);
        return parseEndpoint(node, payload, "EchoRelay");
    }

    private static void putPortRange(JSONObject payload, Node node) {
        if (node.getPortSta() != null && node.getPortEnd() != null
                && node.getPortSta() > 0 && node.getPortEnd() >= node.getPortSta()) {
            payload.put("portStart", node.getPortSta());
            payload.put("portEnd", node.getPortEnd());
        }
    }

    private static DiagEndpoint parseEndpoint(Node node, JSONObject payload, String type) {
        GostDto resp;
        try {
            resp = WebSocketServer.send_msg(node.getId(), payload, type);
        } catch (Exception e) {
            return DiagEndpoint.err("下发异常: " + e.getMessage());
        }
        if (resp == null || !OK.equals(resp.getMsg())) {
            return DiagEndpoint.err(resp == null || resp.getMsg() == null ? "节点无响应" : resp.getMsg());
        }
        if (!(resp.getData() instanceof JSONObject)) {
            return DiagEndpoint.err("节点未返回端口信息");
        }
        JSONObject data = (JSONObject) resp.getData();
        int port = data.getIntValue("port");
        String token = data.getString("token");
        if (port <= 0 || token == null || token.isEmpty()) {
            return DiagEndpoint.err("节点返回的端口信息无效");
        }
        return DiagEndpoint.ok(port, token);
    }

    /**
     * 关闭指定 token 的诊断监听（幂等）。
     *
     * <p><b>异步</b>执行：清理不能留在 HTTP 请求的关键路径上——节点无响应时每次
     * 阻塞等待都会白吃一个 10s 的命令窗口。节点侧本身有自动过期兜底，
     * 即使这条命令丢失也不会遗留监听。</p>
     */
    public static void stopDiag(Node node, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        DIAG_POOL.submit(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("token", token);
                WebSocketServer.send_msg(node.getId(), payload, "StopDiag");
            } catch (Exception e) {
                log.warn("关闭节点[{}]诊断监听失败: {}", node.getId(), e.getMessage());
            }
        });
    }

    /**
     * 从探测节点发起端到端真实数据回环。
     *
     * @param prober      发起探测的节点（通常为入口节点）
     * @param targetHost  链路第一跳地址（overlay 入口）
     * @param targetPort  链路第一跳端口
     * @param echoToken   出口 echo 服务的 token（透传校验）
     * @param description 展示文案
     */
    public static DiagnosisResultDto echoProbe(Node prober, String targetHost, int targetPort,
                                               String echoToken, String description) {
        DiagnosisResultDto r = DiagnosisResultDto.of("LOOPBACK", prober.getId(), prober.getName(),
                targetHost, targetPort, description);
        JSONObject payload = new JSONObject();
        payload.put("target", formatHostPort(targetHost, targetPort));
        payload.put("token", echoToken);
        payload.put("rounds", ECHO_ROUNDS);
        payload.put("payloadSize", ECHO_PAYLOAD);
        payload.put("budgetMs", ECHO_BUDGET_MS);
        // 兼容旧节点：旧节点只认 timeoutMs，会把它当作单次操作超时，
        // 故按轮次折算一个较小值，避免旧节点跑出面板窗口。
        payload.put("timeoutMs", ECHO_BUDGET_MS / (ECHO_ROUNDS * 2 + 2));

        GostDto resp;
        try {
            resp = WebSocketServer.send_msg(prober.getId(), payload, "EchoProbe");
        } catch (Exception e) {
            return r.fail("回环命令下发异常: " + e.getMessage());
        }
        if (resp == null || !OK.equals(resp.getMsg())) {
            return r.fail(resp == null || resp.getMsg() == null ? "节点无响应" : resp.getMsg());
        }
        if (!(resp.getData() instanceof JSONObject)) {
            return r.fail("节点未返回回环数据");
        }
        JSONObject data = (JSONObject) resp.getData();
        boolean success = data.getBooleanValue("success");
        r.setRounds(data.getIntValue("rounds"));
        r.setOkRounds(data.getIntValue("okRounds"));
        r.setBytesVerified(data.getLongValue("bytesVerified"));
        r.setIntegrityOk(data.getBooleanValue("integrityOk"));
        r.setPacketLoss(data.getDoubleValue("packetLoss"));
        if (success) {
            r.setSuccess(true);
            r.setMessage("数据往返校验成功");
            r.setAverageTime(data.getDoubleValue("averageTime"));
            r.setMinTime(data.getDoubleValue("minTime"));
            r.setMaxTime(data.getDoubleValue("maxTime"));
            r.setJitter(data.getDoubleValue("jitter"));
        } else {
            String err = data.getString("errorMessage");
            r.setSuccess(false);
            r.setMessage(err != null && !err.isEmpty() ? err : "数据回环失败");
        }
        return r;
    }

    /** IPv6 自动加方括号 */
    public static String formatHostPort(String host, int port) {
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    /**
     * 单节点本地数据面自检：在节点上启动 echo 服务，并由同一节点向 127.0.0.1 发起真实数据往返。
     * 用于端口转发型隧道（单节点、无中转）验证节点进程可正常收发数据。
     */
    public static DiagnosisResultDto localLoopback(Node node, String description) {
        DiagEndpoint echo = startEcho(node);
        if (!echo.ok) {
            return DiagnosisResultDto.of("LOOPBACK", node.getId(), node.getName(), "127.0.0.1", null, description)
                    .fail("无法启动回环服务: " + echo.error);
        }
        try {
            return echoProbe(node, "127.0.0.1", echo.port, echo.token, description);
        } finally {
            stopDiag(node, echo.token);
        }
    }

    private static final class RelayHandle {
        final Node node;
        final String token;

        RelayHandle(Node node, String token) {
            this.node = node;
            this.token = token;
        }
    }

    /**
     * 端到端真实数据回环。
     *
     * <p>在出口节点启动带 token 校验的 echo 服务，并在每一个中转节点上逐跳启动
     * raw TCP 透传，串联出与生产链路一致的节点拓扑；随后由入口节点发起真实数据往返，
     * 逐字节校验完整性并测量 RTT。全过程使用临时端口、不改动节点 gost 配置、自动过期。</p>
     *
     * @param entry       入口节点
     * @param relayNodes  链路中继节点（有序，最后一个为出口节点）
     * @param pathDesc    路径描述文案
     */
    public static DiagnosisResultDto chainLoopback(Node entry, List<Node> relayNodes, String pathDesc) {
        if (relayNodes == null || relayNodes.isEmpty()) {
            return DiagnosisResultDto.of("LOOPBACK", entry.getId(), entry.getName(), null, null,
                    "端到端数据回环").fail("链路缺少出口节点");
        }
        Node exit = relayNodes.get(relayNodes.size() - 1);
        List<Node> mids = relayNodes.subList(0, relayNodes.size() - 1);

        DiagEndpoint echo = startEcho(exit);
        if (!echo.ok) {
            return DiagnosisResultDto.of("LOOPBACK", exit.getId(), exit.getName(), exit.getServerIp(), null,
                    "端到端数据回环" + (pathDesc == null ? "" : "(" + pathDesc + ")"))
                    .fail("无法在出口节点[" + exit.getName() + "]启动回环服务: " + echo.error);
        }

        List<RelayHandle> relays = new ArrayList<>();
        try {
            String probeHost = exit.getServerIp();
            int probePort = echo.port;
            String nextHop = formatHostPort(exit.getServerIp(), echo.port);

            for (int i = mids.size() - 1; i >= 0; i--) {
                Node mid = mids.get(i);
                DiagEndpoint relay = startRelay(mid, nextHop);
                if (!relay.ok) {
                    return DiagnosisResultDto.of("LOOPBACK", mid.getId(), mid.getName(), mid.getServerIp(), null,
                            "端到端数据回环" + (pathDesc == null ? "" : "(" + pathDesc + ")"))
                            .fail("无法在中转节点[" + mid.getName() + "]建立回环中转: " + relay.error);
                }
                relays.add(new RelayHandle(mid, relay.token));
                probeHost = mid.getServerIp();
                probePort = relay.port;
                nextHop = formatHostPort(mid.getServerIp(), relay.port);
            }

            String desc = "端到端数据回环" + (pathDesc == null ? "" : "(" + pathDesc + ")");
            return echoProbe(entry, probeHost, probePort, echo.token, desc);
        } finally {
            stopDiag(exit, echo.token);
            for (RelayHandle h : relays) {
                stopDiag(h.node, h.token);
            }
        }
    }
}
