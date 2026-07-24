package com.admin.service;

import com.admin.common.dto.FlowBatchDto;
import com.admin.common.dto.FlowDto;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowAccountingService {

    private static final int MAX_INSTANCE_ID_LENGTH = 64;

    private final JdbcTemplate jdbcTemplate;
    private final ForwardService forwardService;
    private final TunnelService tunnelService;
    private final UserTunnelService userTunnelService;

    @Transactional
    public AccountingResult accountBatch(Node node, FlowBatchDto batch) {
        validateBatch(node, batch);

        long now = System.currentTimeMillis();
        jdbcTemplate.update("""
                INSERT IGNORE INTO flow_report_stream
                    (node_id, instance_id, started_at, last_sequence, created_time, updated_time)
                VALUES (?, ?, ?, 0, ?, ?)
                """, node.getId(), batch.getInstanceId(), batch.getStartedAt(), now, now);

        ReportStream stream = jdbcTemplate.queryForObject("""
                SELECT instance_id, started_at, last_sequence
                FROM flow_report_stream
                WHERE node_id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new ReportStream(
                rs.getString("instance_id"),
                rs.getLong("started_at"),
                rs.getLong("last_sequence")), node.getId());

        if (stream == null) {
            throw new IllegalStateException("无法锁定节点流量上报状态");
        }

        if (!Objects.equals(stream.instanceId, batch.getInstanceId())) {
            if (batch.getStartedAt() <= stream.startedAt) {
                return collectAffectedTargets(node, batch.getItems(), true);
            }
            jdbcTemplate.update("""
                    UPDATE flow_report_stream
                    SET instance_id = ?, started_at = ?, last_sequence = 0, updated_time = ?
                    WHERE node_id = ?
                    """, batch.getInstanceId(), batch.getStartedAt(), now, node.getId());
            stream = new ReportStream(batch.getInstanceId(), batch.getStartedAt(), 0);
        }

        if (batch.getSequence() <= stream.lastSequence) {
            return collectAffectedTargets(node, batch.getItems(), true);
        }

        AccountingResult result = new AccountingResult(false);
        for (FlowDto item : batch.getItems()) {
            accountAbsoluteItem(node, batch.getInstanceId(), item, result, now);
        }

        jdbcTemplate.update("""
                UPDATE flow_report_stream
                SET last_sequence = ?, updated_time = ?
                WHERE node_id = ? AND instance_id = ?
                """, batch.getSequence(), now, node.getId(), batch.getInstanceId());
        return result;
    }

    @Transactional
    public AccountingResult accountLegacy(Node node, FlowDto item) {
        if (node == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        AccountingResult result = new AccountingResult(false);
        FlowTarget target = resolveTarget(node, item);
        if (target == null) {
            return result;
        }
        long up = nonNegative(item.getU());
        long down = nonNegative(item.getD());
        applyDelta(target, up, down, result);
        return result;
    }

    private void accountAbsoluteItem(
            Node node, String instanceId, FlowDto item, AccountingResult result, long now) {
        if (item == null || item.getN() == null || item.getN().endsWith("_tls")) {
            return;
        }

        long absoluteUp = nonNegative(item.getU());
        long absoluteDown = nonNegative(item.getD());
        long generation = item.getG() == null ? 0 : item.getG();
        List<CounterCursor> cursors = jdbcTemplate.query("""
                SELECT instance_id, service_generation, last_up, last_down
                FROM flow_report_cursor
                WHERE node_id = ? AND service_name = ?
                FOR UPDATE
                """, (rs, rowNum) -> new CounterCursor(
                rs.getString("instance_id"),
                rs.getLong("service_generation"),
                rs.getLong("last_up"),
                rs.getLong("last_down")), node.getId(), item.getN());

        long deltaUp = absoluteUp;
        long deltaDown = absoluteDown;
        if (!cursors.isEmpty()
                && Objects.equals(cursors.get(0).instanceId, instanceId)
                && cursors.get(0).generation == generation) {
            CounterCursor cursor = cursors.get(0);
            deltaUp = absoluteDelta(cursor.lastUp, absoluteUp);
            deltaDown = absoluteDelta(cursor.lastDown, absoluteDown);
        }

        jdbcTemplate.update("""
                INSERT INTO flow_report_cursor
                    (node_id, service_name, instance_id, service_generation,
                     last_up, last_down, created_time, updated_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    instance_id = VALUES(instance_id),
                    service_generation = VALUES(service_generation),
                    last_up = VALUES(last_up),
                    last_down = VALUES(last_down),
                    updated_time = VALUES(updated_time)
                """, node.getId(), item.getN(), instanceId,
                generation, absoluteUp, absoluteDown, now, now);

        if (deltaUp == 0 && deltaDown == 0) {
            return;
        }

        FlowTarget target = resolveTarget(node, item);
        if (target != null) {
            applyDelta(target, deltaUp, deltaDown, result);
        }
    }

    private AccountingResult collectAffectedTargets(Node node, List<FlowDto> items, boolean duplicate) {
        AccountingResult result = new AccountingResult(duplicate);
        if (items == null) {
            return result;
        }
        for (FlowDto item : items) {
            FlowTarget target = resolveTarget(node, item);
            if (target != null) {
                result.userIds.add(target.forward.getUserId());
                if (target.userTunnel != null) {
                    result.userTunnelIds.add(target.userTunnel.getId());
                }
            }
        }
        return result;
    }

    private FlowTarget resolveTarget(Node node, FlowDto item) {
        if (item == null || item.getN() == null || item.getN().endsWith("_tls")) {
            return null;
        }

        String[] ids = item.getN().split("_", 4);
        if (ids.length < 3) {
            log.warn("忽略格式无效的流量服务名: {}", item.getN());
            return null;
        }

        try {
            long forwardId = Long.parseLong(ids[0]);
            int userId = Integer.parseInt(ids[1]);
            int userTunnelId = Integer.parseInt(ids[2]);
            Forward forward = forwardService.getById(forwardId);
            if (forward == null || !Objects.equals(forward.getUserId(), userId)) {
                return null;
            }

            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null || !Objects.equals(tunnel.getInNodeId(), node.getId())) {
                log.warn("节点 {} 尝试上报不属于它的转发 {}", node.getId(), forwardId);
                return null;
            }

            UserTunnel userTunnel = null;
            if (userTunnelId != 0) {
                userTunnel = userTunnelService.getById(userTunnelId);
                if (userTunnel == null
                        || !Objects.equals(userTunnel.getUserId(), forward.getUserId())
                        || !Objects.equals(userTunnel.getTunnelId(), forward.getTunnelId())) {
                    log.warn("转发 {} 的用户隧道标识无效: {}", forwardId, userTunnelId);
                    return null;
                }
            }
            return new FlowTarget(forward, tunnel, userTunnel);
        } catch (NumberFormatException exception) {
            log.warn("忽略格式无效的流量服务名: {}", item.getN());
            return null;
        }
    }

    private void applyDelta(
            FlowTarget target, long rawUp, long rawDown, AccountingResult result) {
        long billedUp = bill(rawUp, target.tunnel);
        long billedDown = bill(rawDown, target.tunnel);
        if (billedUp == 0 && billedDown == 0) {
            return;
        }

        int forwardRows = jdbcTemplate.update("""
                UPDATE `forward`
                SET in_flow = COALESCE(in_flow, 0) + ?,
                    out_flow = COALESCE(out_flow, 0) + ?
                WHERE id = ?
                """, billedDown, billedUp, target.forward.getId());
        int userRows = jdbcTemplate.update("""
                UPDATE `user`
                SET in_flow = COALESCE(in_flow, 0) + ?,
                    out_flow = COALESCE(out_flow, 0) + ?
                WHERE id = ?
                """, billedDown, billedUp, target.forward.getUserId());
        if (forwardRows != 1 || userRows != 1) {
            throw new IllegalStateException("流量记账目标已不存在");
        }

        result.userIds.add(target.forward.getUserId());
        if (target.userTunnel != null) {
            int tunnelRows = jdbcTemplate.update("""
                    UPDATE user_tunnel
                    SET in_flow = COALESCE(in_flow, 0) + ?,
                        out_flow = COALESCE(out_flow, 0) + ?
                    WHERE id = ?
                    """, billedDown, billedUp, target.userTunnel.getId());
            if (tunnelRows != 1) {
                throw new IllegalStateException("用户隧道记账目标已不存在");
            }
            result.userTunnelIds.add(target.userTunnel.getId());
        }
    }

    private static long bill(long value, Tunnel tunnel) {
        if (value == 0) {
            return 0;
        }
        BigDecimal ratio = tunnel.getTrafficRatio() == null
                ? BigDecimal.ONE : tunnel.getTrafficRatio();
        int flowType = tunnel.getFlow() <= 0 ? 2 : tunnel.getFlow();
        BigDecimal billed = BigDecimal.valueOf(value)
                .multiply(ratio)
                .multiply(BigDecimal.valueOf(flowType));
        if (billed.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) {
            return Long.MAX_VALUE;
        }
        return billed.longValue();
    }

    static long absoluteDelta(long previous, long current) {
        return current >= previous ? current - previous : current;
    }

    private static long nonNegative(Long value) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw new IllegalArgumentException("流量计数不能为负数");
        }
        return value;
    }

    private static void validateBatch(Node node, FlowBatchDto batch) {
        if (node == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        if (batch == null
                || batch.getInstanceId() == null
                || batch.getInstanceId().isBlank()
                || batch.getInstanceId().length() > MAX_INSTANCE_ID_LENGTH
                || batch.getStartedAt() == null
                || batch.getStartedAt() <= 0
                || batch.getSequence() == null
                || batch.getSequence() <= 0
                || batch.getItems() == null) {
            throw new IllegalArgumentException("流量批次格式无效");
        }
    }

    private static class ReportStream {
        private final String instanceId;
        private final long startedAt;
        private final long lastSequence;

        private ReportStream(String instanceId, long startedAt, long lastSequence) {
            this.instanceId = instanceId;
            this.startedAt = startedAt;
            this.lastSequence = lastSequence;
        }
    }

    private static class CounterCursor {
        private final String instanceId;
        private final long generation;
        private final long lastUp;
        private final long lastDown;

        private CounterCursor(
                String instanceId, long generation, long lastUp, long lastDown) {
            this.instanceId = instanceId;
            this.generation = generation;
            this.lastUp = lastUp;
            this.lastDown = lastDown;
        }
    }

    private static class FlowTarget {
        private final Forward forward;
        private final Tunnel tunnel;
        private final UserTunnel userTunnel;

        private FlowTarget(Forward forward, Tunnel tunnel, UserTunnel userTunnel) {
            this.forward = forward;
            this.tunnel = tunnel;
            this.userTunnel = userTunnel;
        }
    }

    @Getter
    public static class AccountingResult {
        private final boolean duplicate;
        private final Set<Integer> userIds = new LinkedHashSet<>();
        private final Set<Integer> userTunnelIds = new LinkedHashSet<>();

        private AccountingResult(boolean duplicate) {
            this.duplicate = duplicate;
        }

        public Set<Integer> getUserIds() {
            return Collections.unmodifiableSet(userIds);
        }

        public Set<Integer> getUserTunnelIds() {
            return Collections.unmodifiableSet(userTunnelIds);
        }
    }
}
