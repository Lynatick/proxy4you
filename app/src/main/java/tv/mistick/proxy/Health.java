package tv.mistick.proxy;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Проверка ноды настоящим TLS-рукопожатием с YouTube через неё. */
final class Health {

    private static final String TAG = "MiStickProxy";
    static final String TARGET_HOST = "www.youtube.com";
    static final int TARGET_PORT = 443;

    /** Адрес, по которому проверяется доступность ноды. */
    static String target() {
        return TARGET_HOST + ":" + TARGET_PORT;
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
            int latency = (int) (System.currentTimeMillis() - started);
            Log.d(TAG, "нода " + node.host + ":" + node.port + " работает через " + target() + ": " + latency + " мс");
            return latency;
        } catch (Exception error) {
            Log.d(TAG, "нода " + node.host + ":" + node.port + " не отвечает: " + error.getMessage());
            return -1;
        } finally {
            closeQuietly(tls);
            closeQuietly(tunnel);
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
