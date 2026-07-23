package com.admin.service.impl;

import com.admin.common.dto.ForwardPortAvailabilityDto;
import com.admin.entity.Node;
import com.admin.entity.UserTunnel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardServiceImplTest {

    @Test
    void keepsUserLimiterWhenUpdatingForward() {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setSpeedId(88);

        assertEquals(88, ForwardServiceImpl.resolveLimiterForUpdate(userTunnel, false));
    }

    @Test
    void keepsAdministratorOwnedForwardUnlimited() {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setSpeedId(88);

        assertNull(ForwardServiceImpl.resolveLimiterForUpdate(userTunnel, true));
        assertNull(ForwardServiceImpl.resolveLimiterForUpdate(null, false));
    }

    @Test
    void reportsOccupiedAndAvailableEntrancePorts() {
        Node node = new Node();
        node.setPortSta(10000);
        node.setPortEnd(20000);

        ForwardPortAvailabilityDto occupied = ForwardServiceImpl.evaluateInPortAvailability(
                node, 15000, Set.of(15000, 16000));
        ForwardPortAvailabilityDto available = ForwardServiceImpl.evaluateInPortAvailability(
                node, 17000, Set.of(15000, 16000));

        assertFalse(occupied.isAvailable());
        assertEquals("入口端口 15000 已被占用，请更换端口", occupied.getMessage());
        assertTrue(available.isAvailable());
        assertEquals("入口端口 17000 可用", available.getMessage());
    }

    @Test
    void reportsPortOutsideNodeRange() {
        Node node = new Node();
        node.setPortSta(10000);
        node.setPortEnd(20000);

        ForwardPortAvailabilityDto result = ForwardServiceImpl.evaluateInPortAvailability(
                node, 9999, Set.of());

        assertFalse(result.isAvailable());
        assertEquals("端口 9999 不在入口节点允许范围 10000-20000 内", result.getMessage());
    }
}
