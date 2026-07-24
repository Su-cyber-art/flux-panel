package com.admin.controller;

import com.admin.entity.Forward;
import com.admin.service.ForwardService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FlowControllerTest {

    @Test
    void storesDesiredPausedStateForEveryForward() {
        FlowController controller = new FlowController();
        controller.forwardService = mock(ForwardService.class);

        Forward firstForward = forward(101L, 7, 11);
        Forward secondForward = forward(102L, 7, 12);

        controller.pauseService(List.of(firstForward, secondForward));

        verify(controller.forwardService).requestForwardStatus(101L, 0);
        verify(controller.forwardService).requestForwardStatus(102L, 0);
    }

    private Forward forward(Long id, Integer userId, Integer tunnelId) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setUserId(userId);
        forward.setTunnelId(tunnelId);
        forward.setStatus(1);
        return forward;
    }

}
