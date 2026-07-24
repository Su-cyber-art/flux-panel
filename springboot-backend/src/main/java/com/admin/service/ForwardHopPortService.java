package com.admin.service;

import com.admin.entity.ForwardHopPort;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ForwardHopPortService extends IService<ForwardHopPort> {

    List<ForwardHopPort> listByForwardId(Long forwardId);

    Map<Long, Integer> getPortMap(Long forwardId);

    void replaceForwardPorts(Long forwardId, List<Long> relayNodeIds, Map<Long, Integer> ports);

    void removeByForwardId(Long forwardId);

    Set<Integer> listUsedPorts(Long nodeId, Long excludeForwardId);
}
