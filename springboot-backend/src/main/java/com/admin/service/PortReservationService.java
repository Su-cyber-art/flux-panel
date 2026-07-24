package com.admin.service;

import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PortReservationService {

    private static final int TUNNEL_FORWARD_TYPE = 2;

    private final JdbcTemplate jdbcTemplate;
    private final NodeService nodeService;
    private final TunnelService tunnelService;
    private final ForwardHopPortService forwardHopPortService;

    public PortAllocation reserveForCreate(Tunnel tunnel, Integer requestedInPort) {
        return reserve(tunnel, requestedInPort, null, true);
    }

    public PortAllocation reserveForUpdate(
            Tunnel tunnel, Integer requestedInPort, Forward existingForward, boolean tunnelChanged) {
        return reserve(tunnel, requestedInPort, existingForward, tunnelChanged);
    }

    private PortAllocation reserve(
            Tunnel tunnel, Integer requestedInPort, Forward existingForward, boolean reallocate) {
        String token = UUID.randomUUID().toString();
        Long existingForwardId = existingForward == null ? null : existingForward.getId();
        String protocol = normalizeProtocol(tunnel.getProtocol());
        Set<PortKey> desired = new LinkedHashSet<>();

        try {
            Integer inPort;
            if (!reallocate && existingForward != null
                    && (requestedInPort == null
                    || requestedInPort.equals(existingForward.getInPort()))) {
                inPort = existingForward.getInPort();
                claimSpecific(tunnel.getInNodeId(), protocol, inPort,
                        token, existingForwardId, "ENTRY", 0, desired);
            } else if (requestedInPort != null) {
                inPort = requestedInPort;
                claimSpecific(tunnel.getInNodeId(), protocol, inPort,
                        token, existingForwardId, "ENTRY", 0, desired);
            } else {
                inPort = claimNext(tunnel.getInNodeId(), protocol,
                        token, existingForwardId, "ENTRY", 0, desired);
            }

            Integer outPort = null;
            Map<Long, Integer> relayPorts = new LinkedHashMap<>();
            if (tunnel.getType() == TUNNEL_FORWARD_TYPE) {
                List<Long> relayNodeIds = tunnelService.getRelayNodeIds(tunnel);
                Map<Long, Integer> existingRelayPorts =
                        existingForward == null || reallocate
                                ? Collections.emptyMap()
                                : forwardHopPortService.getPortMap(existingForward.getId());

                for (int index = 0; index < relayNodeIds.size(); index++) {
                    Long nodeId = relayNodeIds.get(index);
                    Integer existingPort = existingRelayPorts.get(nodeId);
                    Integer port;
                    if (existingPort != null) {
                        port = existingPort;
                        claimSpecific(nodeId, protocol, port, token,
                                existingForwardId, "RELAY", index, desired);
                    } else {
                        port = claimNext(nodeId, protocol, token,
                                existingForwardId, "RELAY", index, desired);
                    }
                    relayPorts.put(nodeId, port);
                }
                outPort = relayPorts.get(tunnel.getOutNodeId());
            }

            return PortAllocation.success(
                    token, protocol, inPort, outPort, relayPorts, desired);
        } catch (PortUnavailableException exception) {
            releaseUnbound(token);
            return PortAllocation.error(exception.getMessage());
        } catch (RuntimeException exception) {
            releaseUnbound(token);
            throw exception;
        }
    }

    public void bindToForward(PortAllocation allocation, Long forwardId) {
        if (allocation == null || allocation.hasError || forwardId == null) {
            throw new IllegalArgumentException("端口预留绑定参数无效");
        }
        long now = System.currentTimeMillis();
        for (PortKey key : allocation.desiredPorts) {
            jdbcTemplate.update("""
                    UPDATE port_reservation
                    SET owner_token = ?, purpose = ?, hop_order = ?, updated_time = ?
                    WHERE node_id = ? AND protocol = ? AND port = ? AND forward_id = ?
                    """, allocation.token, key.purpose, key.hopOrder, now,
                    key.nodeId, key.protocol, key.port, forwardId);
        }
        jdbcTemplate.update("""
                UPDATE port_reservation
                SET forward_id = ?, updated_time = ?
                WHERE owner_token = ? AND forward_id IS NULL
                """, forwardId, now, allocation.token);
    }

    public void releaseObsolete(Long forwardId, String activeToken) {
        if (forwardId != null && activeToken != null) {
            jdbcTemplate.update("""
                    DELETE FROM port_reservation
                    WHERE forward_id = ? AND owner_token <> ?
                      AND EXISTS (
                          SELECT 1
                          FROM `forward`
                          WHERE id = ? AND port_reservation_token = ?
                      )
                    """, forwardId, activeToken, forwardId, activeToken);
        }
    }

    public void releaseUnbound(PortAllocation allocation) {
        if (allocation != null) {
            releaseUnbound(allocation.token);
        }
    }

    public Set<Integer> listUsedPorts(Long nodeId, String protocol, Long excludeForwardId) {
        StringBuilder sql = new StringBuilder("""
                SELECT port
                FROM port_reservation
                WHERE node_id = ? AND protocol = ?
                """);
        if (excludeForwardId != null) {
            sql.append(" AND (forward_id IS NULL OR forward_id <> ?)");
        }
        Object[] args = excludeForwardId == null
                ? new Object[]{nodeId, normalizeProtocol(protocol)}
                : new Object[]{nodeId, normalizeProtocol(protocol), excludeForwardId};
        return new LinkedHashSet<>(jdbcTemplate.query(
                sql.toString(), (rs, rowNum) -> rs.getInt("port"), args));
    }

    public void releaseForward(Long forwardId) {
        if (forwardId != null) {
            jdbcTemplate.update(
                    "DELETE FROM port_reservation WHERE forward_id = ?", forwardId);
        }
    }

    private void claimSpecific(
            Long nodeId,
            String protocol,
            Integer port,
            String token,
            Long existingForwardId,
            String purpose,
            int hopOrder,
            Set<PortKey> desired) {
        Node node = requireNode(nodeId);
        if (port == null || port < node.getPortSta() || port > node.getPortEnd()) {
            throw new PortUnavailableException(
                    "端口 " + port + " 不在节点 " + node.getName()
                            + " 允许范围 " + node.getPortSta() + "-" + node.getPortEnd() + " 内");
        }
        PortKey key = new PortKey(nodeId, protocol, port, purpose, hopOrder);
        if (containsSocket(desired, key)) {
            throw new PortUnavailableException(
                    "节点 " + node.getName() + " 的端口 " + port + " 在当前转发中重复使用");
        }
        if (!tryClaim(key, token, existingForwardId)) {
            throw new PortUnavailableException(
                    "节点 " + node.getName() + " 的端口 " + port + " 已被占用");
        }
        desired.add(key);
    }

    private Integer claimNext(
            Long nodeId,
            String protocol,
            String token,
            Long existingForwardId,
            String purpose,
            int hopOrder,
            Set<PortKey> desired) {
        Node node = requireNode(nodeId);
        if (node.getPortSta() == null || node.getPortEnd() == null) {
            throw new PortUnavailableException("节点 " + node.getName() + " 未配置端口范围");
        }
        for (int port = node.getPortSta(); port <= node.getPortEnd(); port++) {
            PortKey key = new PortKey(nodeId, protocol, port, purpose, hopOrder);
            if (containsSocket(desired, key)) {
                continue;
            }
            if (tryClaim(key, token, existingForwardId)) {
                desired.add(key);
                return port;
            }
        }
        throw new PortUnavailableException("节点 " + node.getName() + " 端口已满");
    }

    private boolean tryClaim(PortKey key, String token, Long existingForwardId) {
        long now = System.currentTimeMillis();
        try {
            jdbcTemplate.update("""
                    INSERT INTO port_reservation
                        (node_id, protocol, port, forward_id, owner_token,
                         purpose, hop_order, created_time, updated_time)
                    VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?)
                    """, key.nodeId, key.protocol, key.port, token,
                    key.purpose, key.hopOrder, now, now);
            return true;
        } catch (DuplicateKeyException exception) {
            if (existingForwardId == null) {
                return false;
            }
            List<Long> owners = jdbcTemplate.query("""
                    SELECT forward_id
                    FROM port_reservation
                    WHERE node_id = ? AND protocol = ? AND port = ?
                    """, (rs, rowNum) -> {
                Number value = (Number) rs.getObject("forward_id");
                return value == null ? null : value.longValue();
            }, key.nodeId, key.protocol, key.port);
            return !owners.isEmpty() && existingForwardId.equals(owners.get(0));
        }
    }

    private Node requireNode(Long nodeId) {
        Node node = nodeService.getNodeById(nodeId);
        if (node == null) {
            throw new PortUnavailableException("节点 " + nodeId + " 不存在");
        }
        return node;
    }

    private void releaseUnbound(String token) {
        if (token != null) {
            jdbcTemplate.update("""
                    DELETE FROM port_reservation
                    WHERE owner_token = ? AND forward_id IS NULL
                    """, token);
        }
    }

    private static boolean containsSocket(Set<PortKey> keys, PortKey candidate) {
        return keys.stream().anyMatch(key ->
                key.nodeId.equals(candidate.nodeId)
                        && key.protocol.equals(candidate.protocol)
                        && key.port.equals(candidate.port));
    }

    static String normalizeProtocol(String protocol) {
        if (protocol != null
                && protocol.toLowerCase(Locale.ROOT).contains("udp")) {
            return "udp";
        }
        return "tcp";
    }

    private record PortKey(
            Long nodeId, String protocol, Integer port, String purpose, int hopOrder) {
    }

    private static class PortUnavailableException extends RuntimeException {
        private PortUnavailableException(String message) {
            super(message);
        }
    }

    @Getter
    public static class PortAllocation {
        private final boolean hasError;
        private final String errorMessage;
        private final String token;
        private final String protocol;
        private final Integer inPort;
        private final Integer outPort;
        private final Map<Long, Integer> relayPorts;
        private final Set<PortKey> desiredPorts;

        private PortAllocation(
                boolean hasError,
                String errorMessage,
                String token,
                String protocol,
                Integer inPort,
                Integer outPort,
                Map<Long, Integer> relayPorts,
                Set<PortKey> desiredPorts) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.token = token;
            this.protocol = protocol;
            this.inPort = inPort;
            this.outPort = outPort;
            this.relayPorts = relayPorts;
            this.desiredPorts = desiredPorts;
        }

        private static PortAllocation success(
                String token,
                String protocol,
                Integer inPort,
                Integer outPort,
                Map<Long, Integer> relayPorts,
                Set<PortKey> desiredPorts) {
            return new PortAllocation(false, null, token, protocol, inPort, outPort,
                    Collections.unmodifiableMap(new LinkedHashMap<>(relayPorts)),
                    Collections.unmodifiableSet(new LinkedHashSet<>(desiredPorts)));
        }

        private static PortAllocation error(String errorMessage) {
            return new PortAllocation(true, errorMessage, null, null,
                    null, null, Collections.emptyMap(), Collections.emptySet());
        }
    }
}
