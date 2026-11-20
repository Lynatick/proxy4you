package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class EngineConfigTest {

    private static final String JSON_SUBSCRIPTION = "{\"outbounds\":["
            + "{\"type\":\"vless\",\"tag\":\"v1\",\"server\":\"1.1.1.1\",\"server_port\":443,"
            + "\"uuid\":\"11111111-1111-1111-1111-111111111111\","
            + "\"tls\":{\"enabled\":true,\"server_name\":\"example.com\",\"utls\":{\"enabled\":true,\"fingerprint\":\"chrome\"}},"
            + "\"transport\":{\"type\":\"tcp\"}},"
            + "{\"type\":\"trojan\",\"tag\":\"t1\",\"server\":\"2.2.2.2\",\"server_port\":443,\"password\":\"pw\"},"
            + "{\"type\":\"selector\",\"tag\":\"sel\",\"outbounds\":[\"v1\"]}"
            + "]}";

    @Test
    public void singBoxConfigHasInboundAndURLTest() throws Exception {
        String config = EngineConfig.singBox(JSON_SUBSCRIPTION, 18080, 0, 19090);
        JSONObject object = new JSONObject(config);

        JSONObject inbound = object.getJSONArray("inbounds").getJSONObject(0);
        assertEquals("mixed", inbound.getString("type"));
        assertEquals(18080, inbound.getInt("listen_port"));

        JSONArray outbounds = object.getJSONArray("outbounds");
        assertEquals("2 ноды + urltest", 3, outbounds.length());
        JSONObject urlTest = outbounds.getJSONObject(2);
        assertEquals("urltest", urlTest.getString("type"));
        assertEquals("auto", urlTest.getString("tag"));
        assertEquals("auto", object.getJSONObject("route").getString("final"));

        assertEquals("127.0.0.1:19090",
                object.getJSONObject("experimental").getJSONObject("clash_api").getString("external_controller"));
    }

    @Test
    public void transportTcpIsRemoved() throws Exception {
        String config = EngineConfig.singBox(JSON_SUBSCRIPTION, 18080, 0, 0);
        JSONObject first = new JSONObject(config).getJSONArray("outbounds").getJSONObject(0);
        assertFalse("transport: tcp запрещён sing-box", first.has("transport"));
    }

    @Test
    public void xrayConfigHasBalancerAndObservatory() throws Exception {
        String config = EngineConfig.xray(JSON_SUBSCRIPTION, 18081, 0);
        JSONObject object = new JSONObject(config);

        JSONObject inbound = object.getJSONArray("inbounds").getJSONObject(0);
        assertEquals("socks", inbound.getString("protocol"));
        assertEquals(18081, inbound.getInt("port"));

        JSONObject balancer = object.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0);
        assertEquals("proxy", balancer.getString("tag"));
        assertEquals("leastPing", balancer.getJSONObject("strategy").getString("type"));
        assertEquals(2, balancer.getJSONArray("selector").length());

        assertNotNull(object.getJSONObject("observatory"));
    }

    @Test
    public void shareLinksAreParsed() throws Exception {
        String links = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?security=reality&sni=x.example&fp=chrome&pbk=key&sid=ab#n\n"
                + "trojan://pw@5.6.7.8:443?sni=t.example#t\n";
        String config = EngineConfig.singBox(links, 18080, 0, 0);
        JSONArray outbounds = new JSONObject(config).getJSONArray("outbounds");
        assertEquals("2 ноды + urltest", 3, outbounds.length());
        assertEquals("vless", outbounds.getJSONObject(0).getString("type"));
    }

    @Test
    public void hysteria2AndTuicAreOnlyForSingBox() throws Exception {
        String links = "hysteria2://pw@7.7.7.7:8443?sni=h.example#h\n"
                + "tuic://11111111-1111-1111-1111-111111111111:pw@8.8.8.8:443?congestion_control=bbr#t\n";
        String singBox = EngineConfig.singBox(links, 18080, 0, 0);
        JSONArray outbounds = new JSONObject(singBox).getJSONArray("outbounds");
        assertEquals("hysteria2", outbounds.getJSONObject(0).getString("type"));
        assertEquals("tuic", outbounds.getJSONObject(1).getString("type"));

        try {
            EngineConfig.xray(links, 18081, 0);
            throw new AssertionError("Xray не должен принимать hysteria2/tuic");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("понятных Xray"));
        }
    }

    @Test
    public void batchesSplitBy200() throws Exception {
        StringBuilder builder = new StringBuilder("{\"outbounds\":[");
        for (int i = 0; i < 250; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"type\":\"socks\",\"tag\":\"n").append(i)
                    .append("\",\"server\":\"10.0.0.1\",\"server_port\":1080}");
        }
        builder.append("]}");
        String json = builder.toString();

        assertEquals(2, EngineConfig.batchCount(json));
        JSONArray first = new JSONObject(EngineConfig.singBox(json, 18080, 0, 0)).getJSONArray("outbounds");
        assertEquals("200 нод + urltest", 201, first.length());
        JSONArray second = new JSONObject(EngineConfig.singBox(json, 18080, 1, 0)).getJSONArray("outbounds");
        assertEquals("50 нод + urltest", 51, second.length());
    }
}
