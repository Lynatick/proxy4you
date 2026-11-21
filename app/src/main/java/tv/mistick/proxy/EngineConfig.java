package tv.mistick.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Генерация конфигов внешних ядер из подписки.
 *
 * Чистая Java без Android API: тот же код проверяется на хосте настоящими
 * бинарниками `sing-box check` и `xray run -test`.
 *
 * Поддерживаются JSON-подписки (sing-box/clash: `outbounds` или `proxies`).
 * Share-ссылки для ядер не конвертируются — они остаются в пуле socks5/http.
 */
final class EngineConfig {

    static final int BATCH_SIZE = 200;

    private EngineConfig() {
    }

    /** Конфиг sing-box: mixed-inbound + пачка outbounds + urltest. */
    static String singBox(String subscriptionJson, int inboundPort, int batchIndex, int controllerPort) throws Exception {
        return singBox(nodesFromText(subscriptionJson), inboundPort, batchIndex, controllerPort);
    }

    /** Конфиг из уже разобранного списка — без повторного парсинга подписки. */
    static String singBox(List<JSONObject> nodes, int inboundPort, int batchIndex, int controllerPort) throws Exception {
        List<JSONObject> batch = batch(nodes, batchIndex);
        if (batch.isEmpty()) {
            throw new IllegalStateException("в подписке нет outbounds для sing-box");
        }

        JSONArray outbounds = new JSONArray();
        JSONArray tags = new JSONArray();
        for (JSONObject node : batch) {
            outbounds.put(new JSONObject(node.toString()));
            tags.put(node.getString("tag"));
        }
        JSONObject auto = new JSONObject();
        auto.put("type", "urltest");
        auto.put("tag", "auto");
        auto.put("outbounds", tags);
        auto.put("url", "https://www.youtube.com/generate_204");
        auto.put("interval", "3m");
        auto.put("tolerance", 100);
        outbounds.put(auto);

        JSONObject inbound = new JSONObject();
        inbound.put("type", "mixed");
        inbound.put("tag", "in");
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", inboundPort);

        JSONObject config = new JSONObject();
        config.put("log", new JSONObject().put("level", "warn").put("timestamp", true));
        config.put("inbounds", new JSONArray().put(inbound));
        config.put("outbounds", outbounds);
        config.put("route", new JSONObject().put("final", "auto"));
        if (controllerPort > 0) {
            config.put("experimental", new JSONObject().put("clash_api",
                    new JSONObject().put("external_controller", "127.0.0.1:" + controllerPort)));
        }
        return config.toString();
    }

    /** Конфиг Xray: socks-inbound + конвертированные outbounds + balancer(leastPing). */
    static String xray(String subscriptionJson, int inboundPort, int batchIndex) throws Exception {
        return xray(nodesFromText(subscriptionJson), inboundPort, batchIndex);
    }

    static String xray(List<JSONObject> nodes, int inboundPort, int batchIndex) throws Exception {
        List<JSONObject> batch = batch(nodes, batchIndex);

        List<JSONObject> converted = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        int skipped = 0;
        for (JSONObject node : batch) {
            JSONObject outbound = toXrayOutbound(node);
            if (outbound == null) {
                skipped++;
                continue;
            }
            converted.add(outbound);
            tags.add(outbound.getString("tag"));
        }
        if (converted.isEmpty()) {
            throw new IllegalStateException("в подписке нет outbounds, понятных Xray");
        }

        JSONArray outbounds = new JSONArray();
        for (JSONObject outbound : converted) {
            outbounds.put(outbound);
        }
        JSONObject direct = new JSONObject();
        direct.put("tag", "direct");
        direct.put("protocol", "freedom");
        direct.put("settings", new JSONObject());
        outbounds.put(direct);

        JSONObject inbound = new JSONObject();
        inbound.put("tag", "socks-in");
        inbound.put("listen", "127.0.0.1");
        inbound.put("port", inboundPort);
        inbound.put("protocol", "socks");
        inbound.put("settings", new JSONObject().put("auth", "noauth").put("udp", true));

        JSONObject balancer = new JSONObject();
        balancer.put("tag", "proxy");
        balancer.put("selector", new JSONArray(tags));
        balancer.put("strategy", new JSONObject().put("type", "leastPing"));

        JSONObject rule = new JSONObject();
        rule.put("type", "field");
        rule.put("network", "tcp,udp");
        rule.put("balancerTag", "proxy");

        JSONObject routing = new JSONObject();
        routing.put("rules", new JSONArray().put(rule));
        routing.put("balancers", new JSONArray().put(balancer));

        JSONObject observatory = new JSONObject();
        observatory.put("subjectSelector", new JSONArray(tags));
        observatory.put("probeURL", "https://www.youtube.com/generate_204");
        observatory.put("probeInterval", "3m");

        JSONObject config = new JSONObject();
        config.put("log", new JSONObject().put("loglevel", "warning"));
        config.put("inbounds", new JSONArray().put(inbound));
        config.put("outbounds", outbounds);
        config.put("routing", routing);
        config.put("observatory", observatory);
        return config.toString();
    }

    // MARK: - Разбор подписки

    /** Outbounds из JSON-подписки или из share-ссылок (base64 тоже). */
    static List<JSONObject> nodesFromText(String text) throws Exception {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            return collectNodes(trimmed);
        }
        if (trimmed.contains("://")) {
            return ShareLinkParser.parse(trimmed);
        }
        String decoded = ShareLinkParser.decodeBase64(trimmed);
        if (decoded == null) {
            return new ArrayList<>();
        }
        String candidate = decoded.trim();
        if (candidate.startsWith("{")) {
            return collectNodes(candidate);
        }
        return ShareLinkParser.parse(candidate);
    }

    static List<JSONObject> collectNodes(String subscriptionJson) throws Exception {
        JSONObject root = new JSONObject(subscriptionJson);
        JSONArray source = root.optJSONArray("outbounds");
        if (source == null) {
            source = root.optJSONArray("proxies");
        }
        if (source == null) {
            return new ArrayList<>();
        }

        List<JSONObject> nodes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> serviceTypes = new LinkedHashSet<>();
        for (String type : new String[]{"selector", "urltest", "direct", "block", "dns", "reject", "fallback"}) {
            serviceTypes.add(type);
        }

        for (int i = 0; i < source.length(); i++) {
            JSONObject prepared = prepare(source.optJSONObject(i), seen, nodes.size());
            if (prepared != null) {
                nodes.add(prepared);
            }
        }
        return nodes;
    }

    /**
     * Приводит outbound к тому, что примет sing-box: `transport: tcp` запрещён
     * (поле нужно убрать), а reality требует uTLS.
     */
    /** Служебные типы, которые не являются узлами. */
    private static final java.util.Set<String> SERVICE_TYPES = new java.util.HashSet<>(
            java.util.Arrays.asList("selector", "urltest", "direct", "block", "dns", "reject", "fallback"));

    /** Проверяет и нормализует один outbound; null — запись не годится. */
    private static JSONObject prepare(JSONObject record, java.util.Set<String> seen, int index) {
        if (record == null) {
            return null;
        }
        String type = record.optString("type", "").toLowerCase();
        if (type.isEmpty() || SERVICE_TYPES.contains(type)) {
            return null;
        }
        if (record.optString("server", "").isEmpty() || record.optInt("server_port", 0) <= 0) {
            return null;
        }
        String tag = record.optString("tag", record.optString("name", ""));
        if (tag.isEmpty()) {
            tag = "n" + index;
        }
        if (!seen.add(tag)) {
            return null;
        }
        try {
            record.put("tag", tag);
        } catch (Exception ignored) {
            return null;
        }
        normalize(record);
        return record;
    }

    /** sing-box принимает только эти отпечатки uTLS; прочие (например, unsafe) → chrome. */
    private static final java.util.Set<String> UTLS_FINGERPRINTS = new java.util.HashSet<>(
            java.util.Arrays.asList("chrome", "firefox", "safari", "ios", "android", "edge",
                    "360", "qq", "random", "randomized"));

    private static void normalize(JSONObject record) {
        try {
            JSONObject transport = record.optJSONObject("transport");
            if (transport != null && "tcp".equalsIgnoreCase(transport.optString("type", ""))) {
                record.remove("transport");
            }
            JSONObject tls = record.optJSONObject("tls");
            if (tls != null && tls.optBoolean("enabled", false)) {
                JSONObject reality = tls.optJSONObject("reality");
                if (reality != null && reality.optBoolean("enabled", false) && !tls.has("utls")) {
                    tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", "chrome"));
                }
                JSONObject utls = tls.optJSONObject("utls");
                if (utls != null) {
                    String fingerprint = utls.optString("fingerprint", "chrome").toLowerCase();
                    if (!UTLS_FINGERPRINTS.contains(fingerprint)) {
                        utls.put("fingerprint", "chrome");
                    }
                }
            }
        } catch (Exception ignored) {
            // нормализация не критична: битый outbound отбросит само ядро
        }
    }

    private static List<JSONObject> batch(List<JSONObject> nodes, int batchIndex) {
        int start = Math.max(0, batchIndex) * BATCH_SIZE;
        if (start >= nodes.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(nodes.subList(start, Math.min(start + BATCH_SIZE, nodes.size())));
    }

    // MARK: - sing-box outbound → Xray outbound

    static JSONObject toXrayOutbound(JSONObject record) {
        String type = record.optString("type", "").toLowerCase();
        String server = record.optString("server", "");
        int port = record.optInt("server_port", record.optInt("port", 0));
        if (server.isEmpty() || port <= 0) {
            return null;
        }
        String tag = record.optString("tag", "n");
        JSONObject stream = streamSettings(record);

        try {
            switch (type) {
                case "vless": {
                    String uuid = record.optString("uuid", "");
                    if (uuid.isEmpty()) return null;
                    JSONObject user = new JSONObject().put("id", uuid)
                            .put("encryption", record.optString("encryption", "none"));
                    String flow = record.optString("flow", "");
                    if (!flow.isEmpty()) user.put("flow", flow);
                    JSONObject vnext = new JSONObject().put("address", server).put("port", port)
                            .put("users", new JSONArray().put(user));
                    return new JSONObject().put("tag", tag).put("protocol", "vless")
                            .put("settings", new JSONObject().put("vnext", new JSONArray().put(vnext)))
                            .put("streamSettings", stream);
                }
                case "vmess": {
                    String uuid = record.optString("uuid", "");
                    if (uuid.isEmpty()) return null;
                    JSONObject user = new JSONObject().put("id", uuid)
                            .put("security", record.optString("security", "auto"));
                    if (record.has("alter_id")) user.put("alterId", record.optInt("alter_id"));
                    JSONObject vnext = new JSONObject().put("address", server).put("port", port)
                            .put("users", new JSONArray().put(user));
                    return new JSONObject().put("tag", tag).put("protocol", "vmess")
                            .put("settings", new JSONObject().put("vnext", new JSONArray().put(vnext)))
                            .put("streamSettings", stream);
                }
                case "trojan": {
                    String password = record.optString("password", "");
                    if (password.isEmpty()) return null;
                    JSONObject serverEntry = new JSONObject().put("address", server).put("port", port)
                            .put("password", password).put("email", "");
                    return new JSONObject().put("tag", tag).put("protocol", "trojan")
                            .put("settings", new JSONObject().put("servers", new JSONArray().put(serverEntry)))
                            .put("streamSettings", stream);
                }
                case "shadowsocks": {
                    String method = record.optString("method", "");
                    String password = record.optString("password", "");
                    if (method.isEmpty() || password.isEmpty()) return null;
                    JSONObject serverEntry = new JSONObject().put("address", server).put("port", port)
                            .put("method", method).put("password", password).put("email", "");
                    return new JSONObject().put("tag", tag).put("protocol", "shadowsocks")
                            .put("settings", new JSONObject().put("servers", new JSONArray().put(serverEntry)));
                }
                case "socks": {
                    JSONObject serverEntry = new JSONObject().put("address", server).put("port", port);
                    String username = record.optString("username", "");
                    if (!username.isEmpty()) {
                        JSONObject users = new JSONObject().put("user", username)
                                .put("pass", record.optString("password", ""));
                        serverEntry.put("users", new JSONArray().put(users));
                    }
                    return new JSONObject().put("tag", tag).put("protocol", "socks")
                            .put("settings", new JSONObject().put("servers", new JSONArray().put(serverEntry)));
                }
                case "http": {
                    JSONObject serverEntry = new JSONObject().put("address", server).put("port", port);
                    String username = record.optString("username", "");
                    if (!username.isEmpty()) {
                        serverEntry.put("users", new JSONArray().put(new JSONObject()
                                .put("user", username).put("pass", record.optString("password", ""))));
                    }
                    return new JSONObject().put("tag", tag).put("protocol", "http")
                            .put("settings", new JSONObject().put("servers", new JSONArray().put(serverEntry)));
                }
                default:
                    return null;
            }
        } catch (Exception error) {
            return null;
        }
    }

    private static JSONObject streamSettings(JSONObject record) {
        JSONObject stream = new JSONObject();
        JSONObject transport = record.optJSONObject("transport");
        String network = transport == null ? "tcp" : transport.optString("type", "tcp");
        try {
            stream.put("network", network);
            if ("ws".equals(network)) {
                JSONObject ws = new JSONObject().put("path", transport == null ? "/" : transport.optString("path", "/"));
                JSONObject headers = transport == null ? null : transport.optJSONObject("headers");
                if (headers != null && headers.has("Host")) {
                    ws.put("headers", new JSONObject().put("Host", headers.optString("Host", "")));
                }
                stream.put("wsSettings", ws);
            } else if ("grpc".equals(network)) {
                stream.put("grpcSettings", new JSONObject().put("serviceName",
                        transport == null ? "" : transport.optString("service_name", "")));
            }

            JSONObject tls = record.optJSONObject("tls");
            if (tls != null && tls.optBoolean("enabled", false)) {
                String sni = tls.optString("server_name", "");
                JSONObject reality = tls.optJSONObject("reality");
                if (reality != null && reality.optBoolean("enabled", false)) {
                    JSONObject utls = tls.optJSONObject("utls");
                    JSONObject settings = new JSONObject();
                    settings.put("serverName", sni);
                    settings.put("publicKey", reality.optString("public_key", ""));
                    settings.put("shortId", reality.optString("short_id", ""));
                    settings.put("fingerprint", utls == null ? "chrome" : utls.optString("fingerprint", "chrome"));
                    settings.put("spiderX", "");
                    stream.put("security", "reality");
                    stream.put("realitySettings", settings);
                } else {
                    JSONObject utls = tls.optJSONObject("utls");
                    stream.put("security", "tls");
                    stream.put("tlsSettings", new JSONObject()
                            .put("serverName", sni)
                            .put("fingerprint", utls == null ? "chrome" : utls.optString("fingerprint", "chrome")));
                }
            }
        } catch (Exception ignored) {
            // без streamSettings outbound останется простым tcp
        }
        return stream;
    }

    static int batchCount(List<JSONObject> nodes) {
        return Math.max(1, (nodes.size() + BATCH_SIZE - 1) / BATCH_SIZE);
    }

    // ------------------------------------------------------------------
    // Разбор больших подписок по одной пачке.
    //
    // Держать в памяти все outbounds нельзя: goida/all.json содержит 57 601
    // outbound, и на 32-битном ТВ (heap 256 МБ) это даёт OutOfMemoryError.
    // Поэтому считаем и извлекаем элементы прямо по тексту, разбирая только
    // нужный диапазон.
    // ------------------------------------------------------------------

    /** Индекс '[' массива outbounds/proxies или -1. */
    private static int arrayStart(String text) {
        for (String key : new String[]{"\"outbounds\"", "\"proxies\""}) {
            int keyIndex = text.indexOf(key);
            if (keyIndex >= 0) {
                int bracket = text.indexOf('[', keyIndex);
                if (bracket >= 0) {
                    return bracket;
                }
            }
        }
        return -1;
    }

    /** Сколько пачек в подписке: JSON считаем сканированием, ссылки — разбором. */
    static int batchCountText(String text) {
        if (!text.trim().startsWith("{")) {
            return batchCount(ShareLinkParser.parse(text));
        }
        int start = arrayStart(text);
        if (start < 0) {
            return 1;
        }
        int count = 0;
        int depth = 1;
        boolean inString = false;
        boolean escape = false;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
                if (depth == 2) {
                    count++;
                }
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        return Math.max(1, (count + BATCH_SIZE - 1) / BATCH_SIZE);
    }

    /** Элементы пачки `batchIndex`; при JSON в память попадает только она. */
    static List<JSONObject> batchFromText(String text, int batchIndex) throws Exception {
        if (!text.trim().startsWith("{")) {
            List<JSONObject> links = ShareLinkParser.parse(text);
            int from = Math.max(0, batchIndex) * BATCH_SIZE;
            if (from >= links.size()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(links.subList(from, Math.min(from + BATCH_SIZE, links.size())));
        }
        List<JSONObject> result = new ArrayList<>();
        int start = arrayStart(text);
        if (start < 0) {
            return result;
        }
        int from = Math.max(0, batchIndex) * BATCH_SIZE;
        int to = from + BATCH_SIZE;
        java.util.Set<String> seen = new java.util.HashSet<>();
        int depth = 1;
        int index = -1;
        int elementStart = -1;
        boolean inString = false;
        boolean escape = false;

        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
                if (depth == 2) {
                    elementStart = i;
                }
            } else if (c == '}' || c == ']') {
                if (depth == 2 && elementStart >= 0) {
                    index++;
                    if (index >= from && index < to) {
                        JSONObject prepared = prepare(
                                new JSONObject(text.substring(elementStart, i + 1)), seen, index);
                        if (prepared != null) {
                            result.add(prepared);
                        }
                    }
                    elementStart = -1;
                    if (index + 1 >= to) {
                        break;
                    }
                }
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        return result;
    }
}
