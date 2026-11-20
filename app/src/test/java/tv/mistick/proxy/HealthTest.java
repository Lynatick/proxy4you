package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class HealthTest {

    private int statusOf(String line) throws Exception {
        // readStatus читает первую строку ответа и требует пробел между версией и кодом.
        return Health.readStatus(new ByteArrayInputStream((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void parsesStatusCodes() throws Exception {
        assertEquals(204, statusOf("HTTP/1.1 204 No Content"));
        assertEquals(403, statusOf("HTTP/1.1 403 Forbidden"));
        assertEquals(301, statusOf("HTTP/1.1 301 Moved Permanently"));
    }

    @Test
    public void garbageGivesMinusOne() throws Exception {
        assertEquals(-1, statusOf("not-a-status-line"));
        assertEquals(-1, statusOf(""));
    }

    @Test
    public void targetIsGenerate204() {
        assertEquals("https://www.youtube.com/generate_204", Health.target());
    }
}
