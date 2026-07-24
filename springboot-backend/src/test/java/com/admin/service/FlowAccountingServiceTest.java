package com.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowAccountingServiceTest {

    @Test
    void calculatesDeltaFromMonotonicAbsoluteCounter() {
        assertEquals(250L, FlowAccountingService.absoluteDelta(1000L, 1250L));
        assertEquals(0L, FlowAccountingService.absoluteDelta(1250L, 1250L));
    }

    @Test
    void treatsCounterResetAsANewAbsoluteInterval() {
        assertEquals(40L, FlowAccountingService.absoluteDelta(1250L, 40L));
    }

    @Test
    void normalizesCurrentTransportProtocolsToTcpSocketFamily() {
        assertEquals("tcp", PortReservationService.normalizeProtocol("tls"));
        assertEquals("tcp", PortReservationService.normalizeProtocol("wss"));
        assertEquals("udp", PortReservationService.normalizeProtocol("udp"));
    }
}
