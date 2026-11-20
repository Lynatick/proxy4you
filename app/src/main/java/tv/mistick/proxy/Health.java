package tv.mistick.proxy;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Проверка ноды настоящим HTTPS-запросом через неё.
 *
 * Запрашиваем `https://www.youtube.com/generate_204` и требуем код 204:
 * TLS-рукопожатия мало — нода может пропускать handshake, но резать запросы.
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

    /** Адрес проверки для интерфейса. */
    static String target() {
        return "https://" + TARGET_HOST + TARGET_PATH;
    }

    private Health() {
    }

    /** Возвращает задержку в миллисекундах или -1, если нода не работает. */
    static int probe(VpnService vpn, NodeStore.Node node, int timeoutMs) {
        long started = System.currentTimeMillis();
        Socket tunnel = null;
        SSLSocket tls = null;
        try {
            tunnel = Upstream.connect(vpn, node, TARGET_HOST, TARGET_PORT, timeoutMs);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            tls = (SSLSocket) factory.createSocket(tunnel, TARGET_HOST, TARGET_PORT, true);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();

            String request = "GET " + TARGET_PATH + " HTTP/1.1\r\n"
                    + "Host: " + TARGET_HOST + "\r\n"
                    + "Connection: close\r\n"
                    + "User-Agent: YouTubeProxyTV\r\n\r\n";
            OutputStream out = tls.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            int status = readStatus(tls.getInputStream());
            lastStatus = status;
            if (status != EXPECTED_STATUS) {
                Log.d(TAG, "нода " + node.host + ":" + node.port + " ответила " + status
                        + " на " + target());
                return -1;
            }

            int latency = (int) (System.currentTimeMillis() - started);
            Log.d(TAG, "нода " + node.host + ":" + node.port + " работает через "
                    + target() + ": " + latency + " мс");
            return latency;
        } catch (Exception error) {
            lastStatus = -1;
            Log.d(TAG, "нода " + node.host + ":" + node.port + " не отвечает: " + error.getMessage());
            return -1;
        } finally {
            closeQuietly(tls);
            closeQuietly(tunnel);
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
