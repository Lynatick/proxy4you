package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Проверка ноды по четырём условиям: разбор стран и список блокировок. */
public class HealthVerifyTest {

    @Test
    public void parsesPlainCountry() {
        assertEquals("RU", Health.parseCountry("RU"));
        assertEquals("DE", Health.parseCountry("de\n"));
        assertEquals("NL", Health.parseCountry("  nl  "));
    }

    @Test
    public void parsesJsonCountry() {
        assertEquals("RU", Health.parseCountry("{\"ip\":\"1.2.3.4\",\"country\":\"RU\"}"));
        assertEquals("FI", Health.parseCountry("{\"country_code\":\"fi\"}"));
        assertEquals("US", Health.parseCountry("{\"countryCode\":\"us\"}"));
    }

    @Test
    public void garbageIsNull() {
        assertNull(Health.parseCountry(""));
        assertNull(Health.parseCountry("<html>blocked</html>"));
        assertNull(Health.parseCountry("{\"detail\":\"rate limited\"}"));
    }

    @Test
    public void blockedCountries() {
        assertTrue(Health.BLOCKED_COUNTRIES.contains("RU"));
        assertTrue(Health.BLOCKED_COUNTRIES.contains("BY"));
        assertFalse(Health.BLOCKED_COUNTRIES.contains("DE"));
        assertFalse(Health.BLOCKED_COUNTRIES.contains("NL"));
    }

    @Test
    public void fourChecksAreConfigured() {
        assertEquals("www.youtube.com", Health.TARGET_HOST);
        assertTrue(Health.VIDEO_HOST.contains("googlevideo.com"));
        assertTrue(Health.GEO_HOST.contains("ipinfo.io"));
        assertTrue(Health.CHATGPT_HOST.contains("chat.openai.com"));
        assertTrue(Health.CHATGPT_PATH.contains("server_status"));
        assertTrue(Health.CHATGPT_AGENT.startsWith("ChatGPT/"));
    }
}
