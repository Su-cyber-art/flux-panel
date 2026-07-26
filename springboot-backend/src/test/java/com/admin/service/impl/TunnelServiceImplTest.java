package com.admin.service.impl;

import com.admin.common.utils.NodeDiagnostics;
import com.admin.common.utils.WebSocketServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断超时预算的回归测试。
 *
 * <p>历史缺陷：旧实现把单次操作超时(6s)误当作总预算，而节点侧会把它分别套在
 * 建连、握手、每轮读、每轮写上，最坏 ~48s，远超面板 10s 的命令响应窗口，
 * 于是面板必然先放弃 → 前端表现为"诊断总是超时"。旧断言只比较了
 * 6000 &lt; 10000，完全没覆盖这个乘数关系。这里按<b>最坏总时长</b>断言。</p>
 */
class TunnelServiceImplTest {

    /** 节点侧每条命令的硬总预算必须留出余量小于面板窗口 */
    @Test
    void probeBudgetsFitWithinCommandResponseWindow() {
        long window = WebSocketServer.COMMAND_RESPONSE_TIMEOUT_MILLIS;
        assertTrue(NodeDiagnostics.TCP_PING_BUDGET_MS < window,
                "逐跳建连的总预算必须小于命令响应窗口");
        assertTrue(NodeDiagnostics.ECHO_BUDGET_MS < window,
                "端到端回环的总预算必须小于命令响应窗口");
    }

    /** 单次建连超时 × 次数不得反过来超过总预算，否则预算形同虚设 */
    @Test
    void perAttemptTimeoutIsConsistentWithBudget() {
        assertTrue(NodeDiagnostics.TCP_PING_TIMEOUT_MS <= NodeDiagnostics.TCP_PING_BUDGET_MS,
                "单次建连超时不得大于总预算");
    }

    /**
     * 兼容旧节点：旧节点只认 timeoutMs 且逐操作各用一份。
     * 操作数 = 建连 + 握手写 + 轮次×(写+读)。最坏总时长仍须落在窗口内。
     */
    @Test
    void legacyNodeWorstCaseStillFitsWindow() {
        int operations = NodeDiagnostics.ECHO_ROUNDS * 2 + 2;
        int legacyPerOpTimeout = NodeDiagnostics.ECHO_BUDGET_MS / operations;
        long legacyWorstCase = (long) legacyPerOpTimeout * operations;
        assertTrue(legacyWorstCase < WebSocketServer.COMMAND_RESPONSE_TIMEOUT_MILLIS,
                "旧节点逐操作套用 timeoutMs 时，最坏总时长也必须小于命令响应窗口");
    }

    /** 整轮诊断必须能在前端请求超时前返回（哪怕是部分结果） */
    @Test
    void totalBudgetLeavesRoomForFrontendTimeout() {
        assertTrue(NodeDiagnostics.TOTAL_BUDGET_MS < 30_000L,
                "诊断总预算必须小于前端请求超时，保证返回部分结果而不是超时");
        assertTrue(NodeDiagnostics.TOTAL_BUDGET_MS > NodeDiagnostics.ECHO_BUDGET_MS,
                "总预算至少要装得下一次端到端回环");
    }

    /** 临时监听的存活时间必须覆盖整轮编排，否则回环还没跑就被兜底关掉 */
    @Test
    void diagListenerOutlivesOrchestration() {
        assertTrue(NodeDiagnostics.ECHO_DURATION_MS > NodeDiagnostics.TOTAL_BUDGET_MS,
                "诊断监听的自动过期时间必须长于整轮诊断的总预算");
    }
}
