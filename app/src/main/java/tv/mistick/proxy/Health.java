package tv.mistick.proxy;

import android.net.VpnService;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Проверка ноды настоящими HTTPS-запросами через неё.
 *
 * Одного 204 от YouTube мало: российские ноды его отдают, но видео и ChatGPT
 * через них не работают. Поэтому проверка идёт по четырём условиям, как в
 * macOS-версии:
 *
 * 1. `https://www.youtube.com/generate_204` → 204;
 * 2. видеосервер `https://redirector.googlevideo.com/report_mapping?di=no` → 2xx;
 * 3. страна выхода (`ipinfo.io/country`, резерв `api.country.is`) не из списка
 *    заблокированных: RU, BY, IR, KP, SY, CU;
 * 4. ChatGPT так, как его проверяет официальное приложение:
 *    `https://ios.chat.openai.com/public-api/mobile/server_status/v1` → 200
 *    и `{"status":"normal"}`.
 */
final class Health {

    private static final String TAG = "MiStickProxy";

    static final String TARGET_HOST = "www.youtube.com";
    static final int TARGET_PORT = 443;
    static final String TARGET_PATH = "/generate_204";
    /** Код, который отдаёт YouTube на этот путь при успешном проходе. */
    static final int EXPECTED_STATUS = 204;
    /** Код ответа последней проверки: -1 — сетевой сбой, 0 — ещё не было. */
    static volatile int lastStatus = 0;
    /** Причина последнего отказа (пусто, если нода рабочая). */
    static volatile String lastReason = "";

    static final String VIDEO_HOST = "redirector.googlevideo.com";
    static final String VIDEO_PATH = "/report_mapping?di=no";
    static final String GEO_HOST = "ipinfo.io";
    static final String GEO_PATH = "/country";
    static final String GEO_FALLBACK_HOST = "api.country.is";
    static final String GEO_FALLBACK_PATH = "/";
    static final String CHATGPT_HOST = "ios.chat.openai.com";
    static final String CHATGPT_PATH = "/public-api/mobile/server_status/v1";
    static final String CHATGPT_AGENT = "ChatGPT/1.2024.317 (macOS; 14.0)";

    /** Страны, из которых нет видео YouTube и ChatGPT. */
    static final Set<String> BLOCKED_COUNTRIES = new HashSet<>(
            Arrays.asList("RU", "BY", "IR", "KP", "SY", "CU"));

    /** Адрес проверки для интерфейса. */
    static String target() {
        return "https://" + TARGET_HOST + TARGET_PATH;
    }

    /** Итог проверки ноды. */
    static final class Verdict {
        final boolean ok;
        final int latencyMs;
        final String reason;

        Verdict(boolean ok, int latencyMs, String reason) {
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.reason = reason;
        }
    }

    private Health() {
    }

    /** Ответ ноды: код и тело (тело нужно для страны и статуса ChatGPT). */
    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** Быстрая проверка только по YouTube — для частых опросов. */
    static int probe(VpnService vpn, NodeStore.Node node, int timeoutMs) {
        long started = System.currentTimeMillis();
        try {
            Response response = request(vpn, node, TARGET_HOST, TARGET_PATH, null, timeoutMs);
            lastStatus = response.status;
            if (response.status != EXPECTED_STATUS && response.status != 200) {
                lastReason = "YouTube: " + response.status;
                return -1;
            }
            lastReason = "";
            return (int) (System.currentTimeMillis() - started);
        } catch (Exception error) {
            lastStatus = -1;
            lastReason = "YouTube: " + error.getMessage();
            return -1;
        }
    }

    /** Полная проверка по четырём условиям. */
    static Verdict verify(VpnService vpn, NodeStore.Node node, int timeoutMs) {
        long started = System.currentTimeMillis();
        try {
            Response youtube = request(vpn, node, TARGET_HOST, TARGET_PATH, null, timeoutMs);
            lastStatus = youtube.status;
            if (youtube.status != EXPECTED_STATUS && youtube.status != 200) {
                return fail("YouTube: " + youtube.status);
            }

            Response video = request(vpn, node, VIDEO_HOST, VIDEO_PATH, null, timeoutMs);
            if (video.status < 200 || video.status >= 300) {
                return fail("видео: " + video.status);
            }

            String country = country(vpn, node, timeoutMs);
            if (country == null) {
                return fail("страна не определена");
            }
            if (BLOCKED_COUNTRIES.contains(country)) {
                return fail("страна " + country);
            }

            Response chatGPT = request(vpn, node, CHATGPT_HOST, CHATGPT_PATH, CHATGPT_AGENT, timeoutMs);
            if (chatGPT.status != 200) {
                return fail("ChatGPT: " + chatGPT.status);
            }
            if (chatGPT.body != null && !chatGPT.body.contains("\"normal\"")) {
                return fail("ChatGPT: статус " + shortBody(chatGPT.body));
            }

            lastReason = "";
            int latency = (int) (System.currentTimeMillis() - started);
            Log.d(TAG, "нода " + node.host + ":" + node.port + " прошла все проверки за " + latency + " мс");
            return new Verdict(true, latency, "");
        } catch (Exception error) {
            lastStatus = -1;
            return fail(String.valueOf(error.getMessage()));
        }
    }

    private static Verdict fail(String reason) {
        lastReason = reason;
        Log.d(TAG, "нода не прошла проверку: " + reason);
        return new Verdict(false, -1, reason);
    }

    private static String shortBody(String body) {
        String text = body.trim();
        return text.length() > 60 ? text.substring(0, 60) : text;
    }

    /** Страна выхода: основной сервис, затем резерв. */
    private static String country(VpnService vpn, NodeStore.Node node, int timeoutMs) throws IOException {
        String primary = parseCountry(request(vpn, node, GEO_HOST, GEO_PATH, null, timeoutMs).body);
        if (primary != null) {
            return primary;
        }
        try {
            return parseCountry(request(vpn, node, GEO_FALLBACK_HOST, GEO_FALLBACK_PATH, null, timeoutMs).body);
        } catch (Exception error) {
            return null;
        }
    }

    /** Разбор ответа geo-сервиса: `RU`, `{"country":"RU"}` или `{"country_code":"RU"}`. */
    static String parseCountry(String body) {
        if (body == null) {
            return null;
        }
        String text = body.trim();
        if (text.startsWith("{")) {
            for (String key : new String[]{"\"country\"", "\"country_code\"", "\"countryCode\""}) {
                int index = text.indexOf(key);
                if (index < 0) {
                    continue;
                }
                int colon = text.indexOf(':', index);
                int start = text.indexOf('"', colon + 1);
                int end = start < 0 ? -1 : text.indexOf('"', start + 1);
                if (start >= 0 && end > start) {
                    String value = text.substring(start + 1, end).trim();
                    if (value.length() >= 2) {
                        return value.substring(0, 2).toUpperCase();
                    }
                }
            }
            return null;
        }
        if (text.length() < 2 || text.length() > 3) {
            return null;
        }
        String letters = text.substring(0, 2).toUpperCase();
        for (int i = 0; i < letters.length(); i++) {
            if (!Character.isLetter(letters.charAt(i))) {
                return null;
            }
        }
        return letters;
    }

    /** HTTPS-запрос через ноду: код ответа и тело. */
    static Response request(VpnService vpn, NodeStore.Node node, String host, String path,
                            String userAgent, int timeoutMs) throws IOException {
        Socket tunnel = null;
        SSLSocket tls = null;
        try {
            tunnel = Upstream.connect(vpn, node, host, TARGET_PORT, timeoutMs);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            tls = (SSLSocket) factory.createSocket(tunnel, host, TARGET_PORT, true);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();

            StringBuilder request = new StringBuilder();
            request.append("GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("User-Agent: ").append(userAgent == null ? "YouTubeProxyTV" : userAgent)
                    .append("\r\n\r\n");
            OutputStream out = tls.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = tls.getInputStream();
            int status = readStatus(in);
            return new Response(status, readBody(in, 2048));
        } catch (Exception error) {
            throw new IOException(host + ": " + error.getMessage(), error);
        } finally {
            closeQuietly(tls);
            closeQuietly(tunnel);
        }
    }

    /** Тело ответа без заголовков: ищем пустую строку, затем читаем до конца. */
    private static String readBody(InputStream in, int limit) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int matched = 0;
            int value;
            while ((value = in.read()) >= 0 && buffer.size() < limit) {
                buffer.write(value);
                if (value == '\n') {
                    matched++;
                    if (matched == 2) {
                        break;
                    }
                } else if (value != '\r') {
                    matched = 0;
                }
            }
            String text = buffer.toString("UTF-8");
            int split = text.indexOf("\r\n\r\n");
            if (split >= 0) {
                return text.substring(split + 4);
            }
            return "";
        } catch (Exception error) {
            return "";
        }
    }

    static int readStatus(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = in.read()) >= 0) {
            char c = (char) value;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.append(c);
            }
            if (line.length() > 256) {
                break;
            }
        }
        String[] parts = line.toString().split(" ");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
        }
    }
}
