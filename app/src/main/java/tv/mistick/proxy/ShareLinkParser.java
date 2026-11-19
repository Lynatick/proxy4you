package tv.mistick.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор share-ссылок подписки в outbounds формата sing-box.
 *
 * Дальше один и тот же список использует и sing-box (как есть), и Xray
 * (конвертацией в {@link EngineConfig#toXrayOutbound}). Поддерживаются
 * vless, trojan, shadowsocks, vmess, socks и http — в том числе base64-подписки.
 */
final class ShareLinkParser {

    private ShareLinkParser() {
    }

    /** Пустой список, если в тексте нет разбираемых ссылок. */
    static List<JSONObject> parse(String text) {
        String content = text;
        if (!content.contains("://")) {
            String decoded = decodeBase64(content);
            if (decoded != null) {
                content = decoded;
            }
        }

        List<JSONObject> outbounds = new ArrayList<>();
        for (String rawLine : content.split("[\\r\\n]+")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("://")) {
                continue;
            }
            JSONObject outbound = convert(line);
            if (outbound != null) {
                try {
                    outbound.put("tag", "n" + outbounds.size());
                } catch (Exception ignored) {
                    // без тега ядро всё равно примет outbound
                }
                outbounds.add(outbound);
            }
        }
        return outbounds;
    }

    static JSONObject convert(String line) {
        int schemeEnd = line.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        String scheme = line.substring(0, schemeEnd).toLowerCase();
        try {
            switch (scheme) {
                case "vless":
                    return vless(line);
                case "trojan":
                    return trojan(line);
                case "ss":
                case "shadowsocks":
                    return shadowsocks(line);
                case "vmess":
                    return vmess(line);
                case "socks":
                case "socks5":
                    return simple(line, "socks");
                case "http":
                case "https":
                    return simple(line, "http");
                default:
                    return null;
            }
        } catch (Exception error) {
            return null;
        }
    }

    // MARK: - Ссылки

    private static JSONObject vless(String line) throws Exception {
        Parsed parsed = parseUri(line);
        if (parsed == null || parsed.userinfo.isEmpty()) {
            return null;
        }
        JSONObject outbound = new JSONObject();
        outbound.put("type", "vless");
        outbound.put("server", parsed.host);
        outbound.put("server_port", parsed.port);
        outbound.put("uuid", parsed.userinfo);
        if (parsed.query.containsKey("flow")) {
            outbound.put("flow", parsed.query.get("flow"));
        }
        JSONObject tls = tlsSettings(parsed);
        if (tls != null) {
            outbound.put("tls", tls);
        }
        JSONObject transport = transportSettings(parsed);
        if (transport != null) {
            outbound.put("transport", transport);
        }
        return outbound;
    }

    private static JSONObject trojan(String line) throws Exception {
        Parsed parsed = parseUri(line);
        if (parsed == null || parsed.userinfo.isEmpty()) {
            return null;
        }
        JSONObject outbound = new JSONObject();
        outbound.put("type", "trojan");
        outbound.put("server", parsed.host);
        outbound.put("server_port", parsed.port);
        outbound.put("password", parsed.userinfo);
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", parsed.query.containsKey("sni")
                ? parsed.query.get("sni") : parsed.query.getOrDefault("host", parsed.host));
        outbound.put("tls", tls);
        return outbound;
    }

    private static JSONObject shadowsocks(String line) throws Exception {
        // SIP002: ss://base64(method:password)@host:port#name
        // legacy:  ss://base64(method:password@host:port)#name
        String body = line.substring(line.indexOf("://") + 3);
        int hash = body.indexOf('#');
        if (hash >= 0) {
            body = body.substring(0, hash);
        }

        String host = "";
        int port = 0;
        String credentials = body;

        int at = body.lastIndexOf('@');
        if (at >= 0) {
            credentials = body.substring(0, at);
            String authority = body.substring(at + 1);
            int colon = authority.lastIndexOf(':');
            if (colon > 0) {
                host = authority.substring(0, colon);
                port = Integer.parseInt(authority.substring(colon + 1));
            }
        }

        String decoded = decodeBase64(credentials);
        if (decoded == null) {
            decoded = credentials;
        }

        if (host.isEmpty() && decoded.contains("@")) {
            String[] parts = decoded.split("@", 2);
            String authority = parts[1];
            int colon = authority.lastIndexOf(':');
            if (colon > 0) {
                host = authority.substring(0, colon);
                port = Integer.parseInt(authority.substring(colon + 1));
            }
            decoded = parts[0];
        }

        int colon = decoded.indexOf(':');
        if (host.isEmpty() || port <= 0 || colon <= 0) {
            return null;
        }

        JSONObject outbound = new JSONObject();
        outbound.put("type", "shadowsocks");
        outbound.put("server", host);
        outbound.put("server_port", port);
        outbound.put("method", decoded.substring(0, colon));
        outbound.put("password", decoded.substring(colon + 1));
        return outbound;
    }

    private static JSONObject vmess(String line) throws Exception {
        String encoded = line.substring(line.indexOf("://") + 3);
        String decoded = decodeBase64(encoded);
        if (decoded == null) {
            return null;
        }
        JSONObject source = new JSONObject(decoded);
        String address = source.optString("add", "");
        int port = source.optInt("port", 0);
        if (address.isEmpty() || port <= 0) {
            return null;
        }

        JSONObject outbound = new JSONObject();
        outbound.put("type", "vmess");
        outbound.put("server", address);
        outbound.put("server_port", port);
        outbound.put("uuid", source.optString("id", ""));
        outbound.put("security", source.optString("scy", "auto"));
        if (source.has("aid")) {
            outbound.put("alter_id", source.optInt("aid", 0));
        }

        String network = source.optString("net", "tcp");
        if ("ws".equals(network)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "ws");
            transport.put("path", source.optString("path", "/"));
            String host = source.optString("host", "");
            if (!host.isEmpty()) {
                transport.put("headers", new JSONObject().put("Host", host));
            }
            outbound.put("transport", transport);
        }

        if ("tls".equals(source.optString("tls", ""))) {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            tls.put("server_name", source.optString("sni", address));
            outbound.put("tls", tls);
        }
        return outbound;
    }

    private static JSONObject simple(String line, String type) throws Exception {
        Parsed parsed = parseUri(line);
        if (parsed == null) {
            return null;
        }
        JSONObject outbound = new JSONObject();
        outbound.put("type", type);
        outbound.put("server", parsed.host);
        outbound.put("server_port", parsed.port);
        if (!parsed.userinfo.isEmpty()) {
            String[] parts = parsed.userinfo.split(":", 2);
            outbound.put("username", parts[0]);
            if (parts.length == 2) {
                outbound.put("password", parts[1]);
            }
        }
        return outbound;
    }

    // MARK: - Общие части

    private static JSONObject tlsSettings(Parsed parsed) throws Exception {
        String security = parsed.query.getOrDefault("security", "none");
        if (!"tls".equals(security) && !"reality".equals(security)) {
            return null;
        }
        String sni = parsed.query.containsKey("sni")
                ? parsed.query.get("sni")
                : parsed.query.getOrDefault("host", parsed.host);
        String fingerprint = parsed.query.getOrDefault("fp", "chrome");

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", sni);
        tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));

        if ("reality".equals(security)) {
            JSONObject reality = new JSONObject();
            reality.put("enabled", true);
            reality.put("public_key", parsed.query.getOrDefault("pbk", ""));
            reality.put("short_id", parsed.query.getOrDefault("sid", ""));
            tls.put("reality", reality);
        }
        return tls;
    }

    private static JSONObject transportSettings(Parsed parsed) throws Exception {
        String type = parsed.query.getOrDefault("type", "tcp");
        JSONObject transport = new JSONObject();
        if ("ws".equals(type)) {
            transport.put("type", "ws");
            transport.put("path", parsed.query.getOrDefault("path", "/"));
            String host = parsed.query.get("host");
            if (host != null && !host.isEmpty()) {
                transport.put("headers", new JSONObject().put("Host", host));
            }
            return transport;
        }
        if ("grpc".equals(type)) {
            transport.put("type", "grpc");
            transport.put("service_name", parsed.query.getOrDefault("serviceName", ""));
            return transport;
        }
        return null;
    }

    private static final class Parsed {
        final String userinfo;
        final String host;
        final int port;
        final Map<String, String> query;

        Parsed(String userinfo, String host, int port, Map<String, String> query) {
            this.userinfo = userinfo;
            this.host = host;
            this.port = port;
            this.query = query;
        }
    }

    private static Parsed parseUri(String line) {
        String rest = line.substring(line.indexOf("://") + 3);

        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }

        Map<String, String> query = new LinkedHashMap<>();
        int question = rest.indexOf('?');
        if (question >= 0) {
            for (String pair : rest.substring(question + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(pair.substring(0, eq), decode(pair.substring(eq + 1)));
                }
            }
            rest = rest.substring(0, question);
        }

        String userinfo = "";
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            userinfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
        }

        int colon = rest.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        String host = rest.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(rest.substring(colon + 1));
        } catch (NumberFormatException error) {
            return null;
        }
        if (host.isEmpty() || port <= 0) {
            return null;
        }
        return new Parsed(userinfo, host, port, query);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    /** Свой Base64: java.util.Base64 требует API 26, android.util недоступен в тестах. */
    static String decodeBase64(String text) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        String input = text.replaceAll("\\s", "");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '=') {
                break;
            }
            int value;
            if (c == '-') {
                value = 62;
            } else if (c == '_') {
                value = 63;
            } else {
                value = alphabet.indexOf(c);
            }
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bytes.write((buffer >> bits) & 0xFF);
            }
        }
        if (bytes.size() == 0) {
            return null;
        }
        try {
            return bytes.toString("UTF-8");
        } catch (Exception error) {
            return null;
        }
    }
}
