package tv.mistick.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Проверка доступности сервисов.
 *
 * Если прокси запущен, запрос идёт через локальный SOCKS5 (то есть тем же путём,
 * что и трафик приложений). Если нет — напрямую, чтобы показать исходную картину.
 */
final class AccessCheck {

    static final class Result {
        final String name;
        final String state;
        final int latencyMs;
        final String detail;

        Result(String name, String state, int latencyMs, String detail) {
            this.name = name;
            this.state = state;
            this.latencyMs = latencyMs;
            this.detail = detail;
        }

        String text() {
            switch (state) {
                case "ok":
                    return latencyMs >= 0 ? "OK · " + latencyMs + " мс" : "OK";
                case "blocked":
                    return "регион";
                case "unknown":
                    return "—";
                default:
                    return "нет ответа";
            }
        }
    }

    static final class Target {
        final String name;
        final String host;
        final String path;
        final boolean forbiddenIsBlocked;

        Target(String name, String host, String path, boolean forbiddenIsBlocked) {
            this.name = name;
            this.host = host;
            this.path = path;
            this.forbiddenIsBlocked = forbiddenIsBlocked;
        }
    }

    static final Target[] TARGETS = {
            new Target("YouTube", "www.youtube.com", "/generate_204", false),
            new Target("ChatGPT", "chatgpt.com", "/api/auth/session", true),
            new Target("Telegram", "api.telegram.org", "/", false),
            new Target("GitHub", "api.github.com", "/zen", false),
            new Target("WhatsApp", "web.whatsapp.com", "/", false),
    };

    private AccessCheck() {
    }

    /** Проверяет все сервисы. `socksPort` = 0 — проверка напрямую. */
    static Result[] check(int socksPort) {
        Result[] results = new Result[TARGETS.length];
        for (int i = 0; i < TARGETS.length; i++) {
            results[i] = probe(TARGETS[i], socksPort);
        }
        return results;
    }

    static Result probe(Target target, int socksPort) {
        long started = System.currentTimeMillis();
        Socket socket = new Socket();
        SSLSocket tls = null;
        try {
            socket.setSoTimeout(9000);
            if (socksPort > 0) {
                socket.connect(new InetSocketAddress("127.0.0.1", socksPort), 6000);
                Upstream.socks5(socket, target.host, 443);
            } else {
                socket.connect(new InetSocketAddress(target.host, 443), 6000);
            }

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            tls = (SSLSocket) factory.createSocket(socket, target.host, 443, true);
            tls.setSoTimeout(9000);
            tls.startHandshake();

            String request = "GET " + target.path + " HTTP/1.1\r\n"
                    + "Host: " + target.host + "\r\n"
                    + "Connection: close\r\n"
                    + "User-Agent: YouTubeProxyTV/1.1\r\n\r\n";
            OutputStream out = tls.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            int status = readStatus(tls.getInputStream());
            int latency = (int) (System.currentTimeMillis() - started);

            if (status == 403 && target.forbiddenIsBlocked) {
                return new Result(target.name, "blocked", latency, "HTTP 403");
            }
            if (status >= 200 && status < 400) {
                return new Result(target.name, "ok", latency, "HTTP " + status);
            }
            return new Result(target.name, "unavailable", -1, "HTTP " + status);
        } catch (Exception error) {
            return new Result(target.name, "unavailable", -1, error.getClass().getSimpleName());
        } finally {
            try {
                if (tls != null) {
                    tls.close();
                } else {
                    socket.close();
                }
            } catch (IOException ignored) {
                // уже закрыто
            }
        }
    }

    private static int readStatus(InputStream in) throws IOException {
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
            if (line.length() > 512) {
                break;
            }
        }
        String statusLine = line.toString();
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}
