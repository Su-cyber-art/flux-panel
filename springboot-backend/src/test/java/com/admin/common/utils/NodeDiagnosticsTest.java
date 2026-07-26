package com.admin.common.utils;

import com.admin.common.dto.DiagnosisResultDto;
import com.admin.entity.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NodeDiagnostics 的纯逻辑测试。
 *
 * <p>这些用例不依赖真实节点：未注册的节点会被 {@link WebSocketServer#send_msg} 立即判为
 * “节点不在线”，因此可用来验证「绝不伪造成功」这一核心不变量。</p>
 */
class NodeDiagnosticsTest {

    private Node offlineNode() {
        Node node = new Node();
        node.setId(999_999L);
        node.setName("离线节点");
        node.setServerIp("203.0.113.7");
        return node;
    }

    @Test
    void tcpProbeOnOfflineNodeIsHonestFailure() {
        DiagnosisResultDto r = NodeDiagnostics.tcpProbe(offlineNode(), "1.1.1.1", 443, "入口->目标", "TARGET");
        assertFalse(r.isSuccess(), "离线节点必须判为失败");
        assertEquals(100.0, r.getPacketLoss());
        assertEquals(-1.0, r.getAverageTime(), "失败时不得伪造 0ms 延迟");
        assertNotNull(r.getMessage());
        assertEquals("TARGET", r.getCategory());
    }

    @Test
    void startEchoOnOfflineNodeReturnsError() {
        NodeDiagnostics.DiagEndpoint ep = NodeDiagnostics.startEcho(offlineNode());
        assertFalse(ep.ok);
        assertNotNull(ep.error);
    }

    @Test
    void startRelayOnOfflineNodeReturnsError() {
        NodeDiagnostics.DiagEndpoint ep = NodeDiagnostics.startRelay(
                offlineNode(), "203.0.113.8:12345", "next-hop-token");
        assertFalse(ep.ok);
        assertNotNull(ep.error);
    }

    @Test
    void localLoopbackOnOfflineNodeFails() {
        DiagnosisResultDto r = NodeDiagnostics.localLoopback(offlineNode(), "自检");
        assertFalse(r.isSuccess());
        assertEquals("LOOPBACK", r.getCategory());
    }

    @Test
    void chainLoopbackWithoutExitFails() {
        DiagnosisResultDto r = NodeDiagnostics.chainLoopback(offlineNode(), java.util.Collections.emptyList(), "空链路");
        assertFalse(r.isSuccess());
    }

    @Test
    void formatHostPortBracketsIpv6() {
        assertEquals("[2001:db8::1]:80", NodeDiagnostics.formatHostPort("2001:db8::1", 80));
        assertEquals("1.2.3.4:80", NodeDiagnostics.formatHostPort("1.2.3.4", 80));
        assertEquals("[2001:db8::1]:80", NodeDiagnostics.formatHostPort("[2001:db8::1]", 80));
        assertEquals("example.com:443", NodeDiagnostics.formatHostPort("example.com", 443));
    }

    @Test
    void diagnosisResultDtoFailResetsMetrics() {
        DiagnosisResultDto d = DiagnosisResultDto.of("HOP", 1L, "n", "1.1.1.1", 80, "desc");
        d.setSuccess(true);
        d.setAverageTime(12.3);
        d.fail("boom");
        assertFalse(d.isSuccess());
        assertEquals("boom", d.getMessage());
        assertEquals(-1.0, d.getAverageTime());
        assertEquals(100.0, d.getPacketLoss());
    }

    @Test
    void summarizeCountsPassesAndFailures() {
        DiagnosisResultDto ok = DiagnosisResultDto.of("HOP", 1L, "a", "1.1.1.1", 80, "x");
        ok.setSuccess(true);
        DiagnosisResultDto bad = DiagnosisResultDto.of("HOP", 2L, "b", "1.1.1.1", 80, "y").fail("no");
        var summary = com.admin.service.impl.ForwardServiceImpl.summarizeDiagnosis(java.util.List.of(ok, bad));
        assertEquals(2, summary.get("total"));
        assertEquals(1, summary.get("passed"));
        assertEquals(1, summary.get("failed"));
        assertTrue(summary.containsKey("failed"));
    }
}
