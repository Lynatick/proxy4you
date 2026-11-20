package tv.mistick.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class ShareLinkParserTest {

    @Test
    public void vlessWithRealityIsParsed() throws Exception {
        String link = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443"
                + "?security=reality&sni=example.com&fp=chrome&pbk=key&sid=abn&type=tcp#node";
        JSONObject outbound = ShareLinkParser.convert(link);

        assertNotNull(outbound);
        assertEquals("vless", outbound.getString("type"));
        assertEquals("1.2.3.4", outbound.getString("server"));
        assertEquals(443, outbound.getInt("server_port"));
        assertEquals("11111111-1111-1111-1111-111111111111", outbound.getString("uuid"));

        JSONObject reality = outbound.getJSONObject("tls").getJSONObject("reality");
        assertEquals("key", reality.getString("public_key"));
        assertEquals("abn", reality.getString("short_id"));
    }

    @Test
    public void trojanIsParsed() throws Exception {
        JSONObject outbound = ShareLinkParser.convert("trojan://secret@5.6.7.8:443?sni=t.example#t");
        assertNotNull(outbound);
        assertEquals("trojan", outbound.getString("type"));
        assertEquals("secret", outbound.getString("password"));
        assertEquals("t.example", outbound.getJSONObject("tls").getString("server_name"));
    }

    @Test
    public void shadowsocksSip002IsParsed() throws Exception {
        JSONObject outbound = ShareLinkParser.convert("ss://YWVzLTI1Ni1nY206cGFzcw==@9.9.9.9:8388#ss");
        assertNotNull(outbound);
        assertEquals("shadowsocks", outbound.getString("type"));
        assertEquals("aes-256-gcm", outbound.getString("method"));
        assertEquals("pass", outbound.getString("password"));
        assertEquals(8388, outbound.getInt("server_port"));
    }

    @Test
    public void socksAndHttpAreParsed() throws Exception {
        JSONObject socks = ShareLinkParser.convert("socks5://user:pw@10.0.0.1:1080#s");
        assertNotNull(socks);
        assertEquals("socks", socks.getString("type"));
        assertEquals("user", socks.getString("username"));

        JSONObject http = ShareLinkParser.convert("http://10.0.0.2:3128#h");
        assertNotNull(http);
        assertEquals("http", http.getString("type"));
    }

    @Test
    public void hysteria2AndTuicAreParsed() throws Exception {
        JSONObject hysteria = ShareLinkParser.convert("hysteria2://pw@7.7.7.7:8443?sni=h.example&insecure=1#h");
        assertNotNull(hysteria);
        assertEquals("hysteria2", hysteria.getString("type"));
        assertEquals(true, hysteria.getJSONObject("tls").getBoolean("insecure"));

        JSONObject tuic = ShareLinkParser.convert(
                "tuic://11111111-1111-1111-1111-111111111111:pw@8.8.8.8:443?congestion_control=bbr&alpn=h3#t");
        assertNotNull(tuic);
        assertEquals("tuic", tuic.getString("type"));
        assertEquals("bbr", tuic.getString("congestion_control"));
    }

    @Test
    public void base64SubscriptionIsDecoded() throws Exception {
        String links = "vless://u@1.1.1.1:443#a\nsocks5://2.2.2.2:1080#b\n";
        String encoded = java.util.Base64.getEncoder().encodeToString(links.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<JSONObject> outbounds = ShareLinkParser.parse(encoded);
        assertEquals(2, outbounds.size());
        assertEquals("vless", outbounds.get(0).getString("type"));
        assertEquals("socks", outbounds.get(1).getString("type"));
    }

    @Test
    public void unknownSchemeIsIgnored() throws Exception {
        assertNull(ShareLinkParser.convert("ftp://example.com:21"));
    }
}
