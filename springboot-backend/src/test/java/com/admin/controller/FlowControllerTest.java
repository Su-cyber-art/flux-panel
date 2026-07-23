package com.admin.controller;

import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowControllerTest {

    @Test
    void pausesEveryForwardWithItsOwnServiceName() {
        TestFlowController controller = new TestFlowController();
        controller.forwardService = mock(ForwardService.class);
        controller.tunnelService = mock(TunnelService.class);
        controller.userTunnelService = mock(UserTunnelService.class);

        Forward firstForward = forward(101L, 7, 11);
        Forward secondForward = forward(102L, 7, 12);
        Tunnel firstTunnel = tunnel(11L, 201L, 1);
        Tunnel secondTunnel = tunnel(12L, 202L, 2);
        UserTunnel firstPermission = userTunnel(501, 7, 11);
        UserTunnel secondPermission = userTunnel(502, 7, 12);

        when(controller.userTunnelService.list(any())).thenReturn(List.of(firstPermission, secondPermission));
        when(controller.tunnelService.getById(11)).thenReturn(firstTunnel);
        when(controller.tunnelService.getById(12)).thenReturn(secondTunnel);
        when(controller.tunnelService.getRelayNodeIds(secondTunnel)).thenReturn(List.of(302L, 303L));

        controller.pauseService(List.of(firstForward, secondForward));

        assertEquals(
                List.of("201:101_7_501", "202:102_7_502"),
                controller.entryCalls);
        assertEquals(
                List.of("302:102_7_502", "303:102_7_502"),
                controller.relayCalls);
        assertEquals(0, firstForward.getStatus());
        assertEquals(0, secondForward.getStatus());
        verify(controller.forwardService).updateById(firstForward);
        verify(controller.forwardService).updateById(secondForward);
    }

    private Forward forward(Long id, Integer userId, Integer tunnelId) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(userId);
        forward.setTunnelId(tunnelId);
        forward.setStatus(1);
        return forward;
    }

    private Tunnel tunnel(Long id, Long inNodeId, Integer type) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(id);
        tunnel.setInNodeId(inNodeId);
        tunnel.setType(type);
        return tunnel;
    }

    private UserTunnel userTunnel(Integer id, Integer userId, Integer tunnelId) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(id);
        userTunnel.setUserId(userId);
        userTunnel.setTunnelId(tunnelId);
        return userTunnel;
    }

    private static class TestFlowController extends FlowController {
        private final List<String> entryCalls = new ArrayList<>();
        private final List<String> relayCalls = new ArrayList<>();

        @Override
        protected void pauseEntryService(Long nodeId, String name) {
            entryCalls.add(nodeId + ":" + name);
        }

        @Override
        protected void pauseRelayService(Long nodeId, String name) {
            relayCalls.add(nodeId + ":" + name);
        }
    }
}
