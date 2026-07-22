package com.admin.service.impl;

import com.admin.entity.ForwardHopPort;
import com.admin.mapper.ForwardHopPortMapper;
import com.admin.service.ForwardHopPortService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ForwardHopPortServiceImpl extends ServiceImpl<ForwardHopPortMapper, ForwardHopPort> implements ForwardHopPortService {

    @Override
    public List<ForwardHopPort> listByForwardId(Long forwardId) {
        if (forwardId == null) {
            return Collections.emptyList();
        }
        return this.list(new QueryWrapper<ForwardHopPort>()
                .eq("forward_id", forwardId)
                .orderByAsc("hop_order"));
    }

    @Override
    public Map<Long, Integer> getPortMap(Long forwardId) {
        Map<Long, Integer> ports = new LinkedHashMap<>();
        for (ForwardHopPort binding : listByForwardId(forwardId)) {
            ports.put(binding.getNodeId(), binding.getPort());
        }
        return ports;
    }

    @Override
    @Transactional
    public void replaceForwardPorts(Long forwardId, List<Long> relayNodeIds, Map<Long, Integer> ports) {
        removeByForwardId(forwardId);
        if (relayNodeIds == null || relayNodeIds.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<ForwardHopPort> bindings = new ArrayList<>(relayNodeIds.size());
        for (int i = 0; i < relayNodeIds.size(); i++) {
            Long nodeId = relayNodeIds.get(i);
            Integer port = ports.get(nodeId);
            if (port == null) {
                throw new IllegalArgumentException("缺少节点 " + nodeId + " 的转发端口");
            }
            ForwardHopPort binding = new ForwardHopPort();
            binding.setForwardId(forwardId);
            binding.setNodeId(nodeId);
            binding.setHopOrder(i);
            binding.setPort(port);
            binding.setCreatedTime(now);
            binding.setUpdatedTime(now);
            bindings.add(binding);
        }
        this.saveBatch(bindings);
    }

    @Override
    public void removeByForwardId(Long forwardId) {
        if (forwardId != null) {
            this.remove(new QueryWrapper<ForwardHopPort>().eq("forward_id", forwardId));
        }
    }

    @Override
    public Set<Integer> listUsedPorts(Long nodeId, Long excludeForwardId) {
        QueryWrapper<ForwardHopPort> query = new QueryWrapper<ForwardHopPort>().eq("node_id", nodeId);
        if (excludeForwardId != null) {
            query.ne("forward_id", excludeForwardId);
        }
        Set<Integer> ports = new LinkedHashSet<>();
        for (ForwardHopPort binding : this.list(query)) {
            ports.add(binding.getPort());
        }
        return ports;
    }
}
