package com.admin.common.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GostUtilTest {

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
