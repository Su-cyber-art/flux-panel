package com.admin.common.utils;

import com.admin.entity.Tunnel;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

class GostUtilTest {

    @Test
    void createsUdpServicesWithFullDatagramBuffers() {
        assertUdpBufferInCommands(false);
    }

    @Test
    void updatesUdpServicesWithFullDatagramBuffers() {
        assertUdpBufferInCommands(true);
    }

    private void assertUdpBufferInCommands(boolean update) {
        for (int tunnelType : List.of(1, 2)) {
            Tunnel tunnel = new Tunnel();
            tunnel.setTcpListenAddr("127.0.0.1");
            tunnel.setUdpListenAddr("127.0.0.1");
            try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
                if (update) {
                    GostUtil.UpdateService(7L, "42_7_0", 10053, null,
                            "127.0.0.1:53", tunnelType, tunnel, "fifo", null);
                } else {
                    GostUtil.AddService(7L, "42_7_0", 10053, null,
                            "127.0.0.1:53", tunnelType, tunnel, "fifo", null);
                }
                ArgumentCaptor<JSONArray> commands = ArgumentCaptor.forClass(JSONArray.class);
                socket.verify(() -> WebSocketServer.send_msg(eq(7L), commands.capture(),
                        eq(update ? "UpdateService" : "AddService")));
                JSONArray services = commands.getValue();
                assertEquals(2, services.size());
                JSONObject tcpListener = services.getJSONObject(0).getJSONObject("listener");
                JSONObject udpListener = services.getJSONObject(1).getJSONObject("listener");
                assertEquals("tcp", tcpListener.getString("type"));
                assertFalse(tcpListener.containsKey("metadata"));
                assertEquals("udp", udpListener.getString("type"));
                assertTrue(udpListener.getJSONObject("metadata").getBooleanValue("keepAlive"));
                // Preserve the wire type: older nodes cannot read a JSON numeric value.
                assertEquals("65536",
                        udpListener.getJSONObject("metadata").get("readBufferSize"));
            }
        }
    }

    @Test
    void createsOneOrderedGostHopPerRelayNode() {
        JSONObject chain = GostUtil.createChainData(
                "42_7_0",
                List.of("relay-a.example:12001", "relay-b.example:13001", "[2001:db8::3]:14001"),
                "tls",
                "eth1");

        JSONArray hops = chain.getJSONArray("hops");
        assertEquals(3, hops.size());
        assertEquals("relay-a.example:12001", getNode(hops, 0).getString("addr"));
        assertEquals("relay-b.example:13001", getNode(hops, 1).getString("addr"));
        assertEquals("[2001:db8::3]:14001", getNode(hops, 2).getString("addr"));
        assertEquals("eth1", getNode(hops, 0).getString("interface"));
        assertFalse(getNode(hops, 1).containsKey("interface"));
        assertEquals("relay", getNode(hops, 2).getJSONObject("connector").getString("type"));
    }

    @Test
    void keepsQuicMetadataOnEveryHop() {
        JSONObject chain = GostUtil.createChainData(
                "quic-chain", List.of("relay-a:1000", "relay-b:2000"), "quic", null);

        JSONArray hops = chain.getJSONArray("hops");
        for (int i = 0; i < hops.size(); i++) {
            JSONObject metadata = getNode(hops, i).getJSONObject("dialer").getJSONObject("metadata");
            assertTrue(metadata.getBooleanValue("keepAlive"));
            assertEquals("10s", metadata.getString("ttl"));
        }
    }

    private JSONObject getNode(JSONArray hops, int index) {
        return hops.getJSONObject(index).getJSONArray("nodes").getJSONObject(0);
    }
}
