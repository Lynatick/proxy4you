package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Потоковый разбор подписки: в память попадает только нужная пачка. */
public class StreamingBatchTest {

    private Path write(String json) throws Exception {
        Path file = Files.createTempFile("sub", ".json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        return file;
    }

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
    public void countsBatchesFromFile() throws Exception {
        Path file = write(subscription(450));
        assertTrue(EngineConfig.looksLikeJson(file.toFile()));
        assertEquals(3, EngineConfig.batchCountFromFile(file.toFile()));
    }

    @Test
    public void slicesBatchesFromFile() throws Exception {
        Path file = write(subscription(450));
        List<JSONObject> first = EngineConfig.batchFromFile(file.toFile(), 0);
        List<JSONObject> second = EngineConfig.batchFromFile(file.toFile(), 1);
        List<JSONObject> third = EngineConfig.batchFromFile(file.toFile(), 2);
        assertEquals(200, first.size());
        assertEquals(200, second.size());
        assertEquals(50, third.size());
        assertEquals("n0", first.get(0).optString("tag"));
        assertEquals("n400", third.get(0).optString("tag"));
    }

    @Test
    public void ignoresBracesInsideStrings() throws Exception {
        Path file = write("{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"a}\\\"b{[\","
                + "\"server\":\"1.1.1.1\",\"server_port\":443,\"uuid\":\"u\"}]}");
        List<JSONObject> nodes = EngineConfig.batchFromFile(file.toFile(), 0);
        assertEquals(1, nodes.size());
        assertEquals("a}\"b{[", nodes.get(0).getString("tag"));
    }

    @Test
    public void linksFileIsNotJson() throws Exception {
        Path file = write("vless://u@1.1.1.1:443?type=tcp#node\nss://YWVzOnB3@2.2.2.2:1080#two\n");
        assertTrue(!EngineConfig.looksLikeJson(file.toFile()));
        assertEquals(2, EngineConfig.linksFromFile(file.toFile()).size());
    }
}
