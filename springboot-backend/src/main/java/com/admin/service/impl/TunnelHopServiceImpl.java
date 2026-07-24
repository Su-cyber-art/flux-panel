package com.admin.service.impl;

import com.admin.entity.Tunnel;
import com.admin.entity.TunnelHop;
import com.admin.mapper.TunnelHopMapper;
import com.admin.service.TunnelHopService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TunnelHopServiceImpl extends ServiceImpl<TunnelHopMapper, TunnelHop> implements TunnelHopService {

    @Override
    public List<TunnelHop> listByTunnelId(Long tunnelId) {
        if (tunnelId == null) {
            return Collections.emptyList();
        }
        return this.list(new QueryWrapper<TunnelHop>()
                .eq("tunnel_id", tunnelId)
                .orderByAsc("hop_order"));
    }

    @Override
    public List<Long> listNodeIds(Long tunnelId) {
        return listByTunnelId(tunnelId).stream()
                .map(TunnelHop::getNodeId)
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> listRelayNodeIds(Tunnel tunnel) {
        if (tunnel == null || tunnel.getType() == null || tunnel.getType() != 2) {
            return Collections.emptyList();
        }
        List<Long> nodeIds = new ArrayList<>(listNodeIds(tunnel.getId()));
        if (tunnel.getOutNodeId() != null) {
            nodeIds.add(tunnel.getOutNodeId());
        }
        return nodeIds;
    }

    @Override
    public List<Long> listPathNodeIds(Tunnel tunnel) {
        if (tunnel == null) {
            return Collections.emptyList();
        }
        List<Long> nodeIds = new ArrayList<>();
        if (tunnel.getInNodeId() != null) {
            nodeIds.add(tunnel.getInNodeId());
        }
        nodeIds.addAll(listRelayNodeIds(tunnel));
        return nodeIds;
    }

    @Override
    @Transactional
    public void replaceTunnelHops(Long tunnelId, List<Long> nodeIds) {
        this.remove(new QueryWrapper<TunnelHop>().eq("tunnel_id", tunnelId));
        if (nodeIds == null || nodeIds.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<TunnelHop> hops = new ArrayList<>(nodeIds.size());
        for (int i = 0; i < nodeIds.size(); i++) {
            TunnelHop hop = new TunnelHop();
            hop.setTunnelId(tunnelId);
            hop.setNodeId(nodeIds.get(i));
            hop.setHopOrder(i);
            hop.setCreatedTime(now);
            hop.setUpdatedTime(now);
            hops.add(hop);
        }
        this.saveBatch(hops);
    }

    @Override
    public long countByNodeId(Long nodeId) {
        return this.count(new QueryWrapper<TunnelHop>().eq("node_id", nodeId));
    }
}
