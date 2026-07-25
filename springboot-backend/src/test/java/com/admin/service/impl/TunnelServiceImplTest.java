package com.admin.service.impl;

import com.admin.common.utils.WebSocketServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelServiceImplTest {

    @Test
    void diagnosisProbeFitsWithinCommandResponseTimeout() {
        long maximumDialTimeMillis =
                (long) TunnelServiceImpl.DIAGNOSIS_TCP_PING_COUNT
                        * TunnelServiceImpl.DIAGNOSIS_TCP_PING_TIMEOUT_MILLIS;

        assertTrue(
                maximumDialTimeMillis < WebSocketServer.COMMAND_RESPONSE_TIMEOUT_MILLIS,
                "TCP probe attempts must finish before the WebSocket response deadline");
    }
}
