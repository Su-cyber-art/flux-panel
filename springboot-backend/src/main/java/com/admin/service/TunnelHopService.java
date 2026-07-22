package com.admin.service;

import com.admin.entity.Tunnel;
import com.admin.entity.TunnelHop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface TunnelHopService extends IService<TunnelHop> {

    List<TunnelHop> listByTunnelId(Long tunnelId);

    List<Long> listNodeIds(Long tunnelId);

    List<Long> listRelayNodeIds(Tunnel tunnel);

    List<Long> listPathNodeIds(Tunnel tunnel);

    void replaceTunnelHops(Long tunnelId, List<Long> nodeIds);

    long countByNodeId(Long nodeId);
}
