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
    void probeBatchKeepsInputOrderAndCount() {
        Node a = offlineNode();
        Node b = new Node();
        b.setId(999_998L);
        b.setName("另一个离线节点");
        b.setServerIp("203.0.113.8");

        var specs = java.util.List.of(
                new NodeDiagnostics.ProbeSpec(a, "1.1.1.1", 80, "第一条", "HOP"),
                new NodeDiagnostics.ProbeSpec(b, "1.1.1.1", 81, "第二条", "HOP"),
                new NodeDiagnostics.ProbeSpec(a, "1.1.1.1", 82, "第三条", "TARGET"));

        var results = NodeDiagnostics.probeBatch(specs);
        assertEquals(3, results.size(), "结果数量必须与输入一致");
        assertEquals("第一条", results.get(0).getDescription());
        assertEquals("第二条", results.get(1).getDescription());
        assertEquals("第三条", results.get(2).getDescription(), "并行执行后仍须按输入顺序返回");
        for (var r : results) {
            assertFalse(r.isSuccess(), "离线节点的探测必须判为失败");
        }
    }

    @Test
    void probeBatchHandlesEmptyAndSingle() {
        assertTrue(NodeDiagnostics.probeBatch(java.util.List.of()).isEmpty());
        assertTrue(NodeDiagnostics.probeBatch(null).isEmpty());
        var one = NodeDiagnostics.probeBatch(java.util.List.of(
                new NodeDiagnostics.ProbeSpec(offlineNode(), "1.1.1.1", 80, "单条", "HOP")));
        assertEquals(1, one.size());
        assertFalse(one.get(0).isSuccess());
    }

    @Test
    void skippedResultIsMarkedAsFailureWithReason() {
        DiagnosisResultDto d = NodeDiagnostics.skipped("LOOPBACK", "端到端数据回环");
        assertFalse(d.isSuccess());
        assertEquals("LOOPBACK", d.getCategory());
        assertNotNull(d.getMessage());
        assertTrue(d.getMessage().contains("跳过"), "被截断的项要让前端能看出是跳过而非失败");
    }

    @Test
    void budgetReportsExhaustionOnceDrained() {
        NodeDiagnostics.Budget fresh = NodeDiagnostics.Budget.standard();
        assertFalse(fresh.exhausted(), "刚创建的预算不应判为耗尽");
        assertTrue(fresh.remainingMs() > 0);

        NodeDiagnostics.Budget drained = NodeDiagnostics.Budget.of(0);
        assertTrue(drained.exhausted(), "零预算必须判为耗尽，从而跳过后续探测");
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
