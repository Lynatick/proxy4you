package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Разбор больших подписок по одной пачке — без полного дерева в памяти. */
public class EngineBatchTest {

    private String subscription(int count) {
        StringBuilder builder = new StringBuilder("{\"outbounds\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"type\":\"socks\",\"tag\":\"n").append(i)
                    .append("\",\"server\":\"1.1.1.1\",\"server_port\":1080}");
        }
        return builder.append("]}").toString();
    }

    @Test
    public void countsAndSlicesBatches() throws Exception {
        String json = subscription(450);
        assertEquals(3, EngineConfig.batchCountText(json));
        assertEquals(200, EngineConfig.batchFromText(json, 0).size());
        assertEquals(200, EngineConfig.batchFromText(json, 1).size());
        assertEquals(50, EngineConfig.batchFromText(json, 2).size());
        assertEquals(0, EngineConfig.batchFromText(json, 3).size());
        assertEquals("n0", EngineConfig.batchFromText(json, 0).get(0).optString("tag"));
        assertEquals("n400", EngineConfig.batchFromText(json, 2).get(0).optString("tag"));
    }

    @Test
    public void scannerIgnoresBracesInsideStrings() throws Exception {
        String json = "{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"a}\\\"b{[\","
                + "\"server\":\"1.1.1.1\",\"server_port\":443,\"uuid\":\"u\"}]}";
        assertEquals(1, EngineConfig.batchCountText(json));
        assertEquals(1, EngineConfig.batchFromText(json, 0).size());
        assertEquals("a}\"b{[", EngineConfig.batchFromText(json, 0).get(0).getString("tag"));
    }

    @Test
    public void emptySubscriptionHasNoBatches() throws Exception {
        assertEquals(1, EngineConfig.batchCountText("{\"outbounds\":[]}"));
        assertEquals(0, EngineConfig.batchFromText("{\"outbounds\":[]}", 0).size());
    }
}
