package tv.mistick.proxy;

import android.net.VpnService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Локальный SOCKS5-сервер внутри приложения.
 *
 * Зачем он нужен: hev-socks5-tunnel умеет только SOCKS5 и создаёт исходящие
 * соединения сам, поэтому его трафик попал бы обратно в TUN. Здесь соединения
 * создаёт наш код: каждое проходит через {@link VpnService#protect}, и уже потом
 * открывается туннель к удалённой ноде (HTTP CONNECT или SOCKS5).
 */
final class Socks5Server {

    private final VpnService vpn;
    private final List<NodeStore.Node> nodes;
    private final AtomicInteger cursor = new AtomicInteger();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private int port;

    Socks5Server(VpnService vpn, List<NodeStore.Node> nodes) {
        this.vpn = vpn;
        this.nodes = nodes;
    }

    int port() {
        return port;
    }

    int start() throws IOException {
        serverSocket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        port = serverSocket.getLocalPort();
        running = true;
        Thread accept = new Thread(this::acceptLoop, "socks-accept");
        accept.setDaemon(true);
        accept.start();
        return port;
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // закрываем — ошибка не важна
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread handler = new Thread(() -> handle(client), "socks-client");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException error) {
                if (running) {
                    continue;
                }
                return;
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            byte[] greeting = Upstream.readFully(in, 2);
            if (greeting[0] != 5) {
                client.close();
                return;
            }
            Upstream.readFully(in, greeting[1] & 0xff);
            out.write(new byte[]{5, 0});
            out.flush();

            byte[] request = Upstream.readFully(in, 4);
            if (request[1] != 1) {
                reply(out, 7);
                client.close();
                return;
            }

            String host;
            int atyp = request[3];
            if (atyp == 1) {
                byte[] raw = Upstream.readFully(in, 4);
                host = (raw[0] & 0xff) + "." + (raw[1] & 0xff) + "." + (raw[2] & 0xff) + "." + (raw[3] & 0xff);
            } else if (atyp == 3) {
                int length = Upstream.readFully(in, 1)[0] & 0xff;
                host = new String(Upstream.readFully(in, length), StandardCharsets.US_ASCII);
            } else if (atyp == 4) {
                host = InetAddress.getByAddress(Upstream.readFully(in, 16)).getHostAddress();
            } else {
                reply(out, 8);
                client.close();
                return;
            }

            byte[] portBytes = Upstream.readFully(in, 2);
            int targetPort = ((portBytes[0] & 0xff) << 8) | (portBytes[1] & 0xff);
            if (targetPort == 0) {
                reply(out, 1);
                client.close();
                return;
            }

            Socket upstream = openThroughAnyNode(host, targetPort);
            if (upstream == null) {
                reply(out, 5);
                client.close();
                return;
            }

            reply(out, 0);
            client.setSoTimeout(0);
            splice(client, upstream);
        } catch (Exception ignored) {
            closeQuietly(client);
        }
    }

    /** Пробует ноды по кругу: одна может быть мертва, следующая — работать. */
    private Socket openThroughAnyNode(String host, int port) {
        int total = nodes.size();
        if (total == 0) {
            return null;
        }
        int attempts = Math.min(3, total);
        int start = cursor.getAndIncrement();
        for (int i = 0; i < attempts; i++) {
            NodeStore.Node node = nodes.get(Math.abs(start + i) % total);
            try {
                return Upstream.connect(vpn, node, host, port, 8000);
            } catch (IOException ignored) {
                // пробуем следующую
            }
        }
        return null;
    }

    private void reply(OutputStream out, int code) throws IOException {
        out.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private void splice(Socket client, Socket upstream) {
        Thread a = new Thread(() -> pump(client, upstream), "socks-up");
        a.setDaemon(true);
        a.start();
        pump(upstream, client);
        closeQuietly(client);
        closeQuietly(upstream);
    }

    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[16384];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            while (true) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // соединение закрылось — нормальный конец
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // уже закрыто
            }
        }
    }

    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // уже закрыто
        }
    }
}
