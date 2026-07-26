package com.admin.service.impl;

import com.admin.common.utils.NodeDiagnostics;
import com.admin.common.utils.WebSocketServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelServiceImplTest {

    @Test
    void tcpProbeFitsWithinCommandResponseTimeout() {
        long maximumDialTimeMillis =
                (long) NodeDiagnostics.TCP_PING_COUNT * NodeDiagnostics.TCP_PING_TIMEOUT_MS;

        assertTrue(
                maximumDialTimeMillis < WebSocketServer.COMMAND_RESPONSE_TIMEOUT_MILLIS,
                "逐跳建连探测必须在命令响应窗口内完成");
    }

    @Test
    void echoProbeFitsWithinCommandResponseTimeout() {
        assertTrue(
                NodeDiagnostics.ECHO_TIMEOUT_MS < WebSocketServer.COMMAND_RESPONSE_TIMEOUT_MILLIS,
                "端到端回环探测必须在命令响应窗口内完成");
    }

    @Test
    void legacyBudgetConstantsStayWithinWindow() {
        long budget =
                (long) TunnelServiceImpl.DIAGNOSIS_TCP_PING_COUNT
                        * TunnelServiceImpl.DIAGNOSIS_TCP_PING_TIMEOUT_MILLIS;
        assertTrue(budget < WebSocketServer.COMMAND_RESPONSE_TIMEOUT_MILLIS);
    }
}
