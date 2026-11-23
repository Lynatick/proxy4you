package tv.mistick.proxy;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Локальный SOCKS5-сервер внутри приложения.
 *
 * Зачем он нужен: hev-socks5-tunnel умеет только SOCKS5 и создаёт исходящие
 * соединения сам, поэтому его трафик попал бы обратно в TUN. Здесь соединения
 * создаёт наш код: каждое проходит через {@link VpnService#protect}, и уже потом
 * открывается туннель к удалённой ноде (HTTP CONNECT или SOCKS5).
 */
final class Socks5Server {

    private static final String TAG = "MiStickProxy";

    private final VpnService vpn;
    private final NodePool pool;
    private volatile EngineRunner engine;
    private volatile boolean running;
    private volatile int engineFailures;
    private ServerSocket serverSocket;
    private int port;

    Socks5Server(VpnService vpn, NodePool pool, EngineRunner engine) {
        this.vpn = vpn;
        this.pool = pool;
        this.engine = engine;
    }

    /** Подменяет ядро без перезапуска SOCKS5-сервера. */
    void setEngine(EngineRunner next) {
        this.engine = next;
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
            if (request[1] == 3) {
                // UDP ASSOCIATE: нужен для DNS (UDP 53) — без него приложения
                // вроде YouTube не могут разрешить имена и пишут «нет сети».
                handleUdpAssociate(client, in, out);
                return;
            }
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

            // Что не входит в выбранные сервисы — идёт напрямую.
            // Весь трафик VPN идёт через прокси; ограничение — на уровне приложений.
            // Как в Hiddify: при запущенном ядре весь трафик идёт через него,
            // ноды пула не подмешиваются — выбором ноды занимается ядро (urltest).
            Socket upstream;
            if (engine != null && engine.isRunning()) {
                upstream = openThroughEngine(host, targetPort);
            } else {
                upstream = openThroughAnyNode(host, targetPort);
            }
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

    /**
     * Открывает туннель через лучшую ноду. Сбойная нода уходит в паузу,
     * запрос сразу пробует следующую — клиент видит только небольшую задержку.
     */
    private Socket openThroughAnyNode(String host, int port) {
        Set<NodePool.Entry> tried = new HashSet<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            List<NodePool.Entry> candidates = pool.except(tried, 1);
            if (candidates.isEmpty()) {
                return null;
            }
            NodePool.Entry entry = candidates.get(0);
            tried.add(entry);
            long started = System.currentTimeMillis();
            try {
                Socket socket = Upstream.connect(vpn, entry.node, host, port, 8000);
                pool.recordSuccess(entry, (int) (System.currentTimeMillis() - started));
                return socket;
            } catch (IOException ignored) {
                pool.recordFailure(entry);
            }
        }
        return null;
    }

    /** Через локальный вход ядра (SOCKS5 на 127.0.0.1). */
    private Socket openThroughEngine(String host, int port) {
        Socket socket = new Socket();
        try {
            if (!vpn.protect(socket)) {
                Log.w(TAG, "protect() отказал для входа ядра — идём напрямую");
            }
            socket.connect(new InetSocketAddress("127.0.0.1", engine.port()), 5000);
            Upstream.socks5(socket, host, port);
            engineFailures = 0;
            return socket;
        } catch (IOException error) {
            closeQuietly(socket);
            if (++engineFailures >= 3) {
                engineFailures = 0;
                ProxyVpnService.requestNextBatch();
            }
            return null;
        }
    }

    /**
     * UDP ASSOCIATE: hev-socks5-tunnel отдаёт сюда UDP-пакеты приложений.
     * Поддерживаем DNS (порт 53) — запрос уходит к серверу имени через прокси,
     * потому что прямой DNS в РФ отдаёт подменённые адреса. Остальной UDP
     * (QUIC) отбрасывается: приложение откатится на TCP.
     */
    private void handleUdpAssociate(Socket client, InputStream in, OutputStream out) {
        DatagramSocket relay = null;
        try {
            relay = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
            int relayPort = relay.getLocalPort();
            out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1,
                    (byte) (relayPort >> 8), (byte) (relayPort & 0xff)});
            out.flush();

            byte[] buffer = new byte[65535];
            relay.setSoTimeout(60000);
            while (running && !client.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    relay.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    break;
                }
                DatagramPacket answer = answerDatagram(packet);
                if (answer != null) {
                    relay.send(answer);
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "UDP ASSOCIATE: " + error);
        } finally {
            if (relay != null) {
                relay.close();
            }
            closeQuietly(client);
        }
    }

    /** Разбирает SOCKS5-UDP пакет и возвращает ответ (null — пакет не поддержан). */
    DatagramPacket answerDatagram(DatagramPacket packet) {
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 10) {
            return null;
        }
        int atyp = data[3] & 0xff;
        int offset = 4;
        String host;
        if (atyp == 1) {
            if (length < offset + 4 + 2) {
                return null;
            }
            host = (data[offset] & 0xff) + "." + (data[offset + 1] & 0xff) + "."
                    + (data[offset + 2] & 0xff) + "." + (data[offset + 3] & 0xff);
            offset += 4;
        } else if (atyp == 3) {
            int nameLength = data[offset] & 0xff;
            if (length < offset + 1 + nameLength + 2) {
                return null;
            }
            host = new String(data, offset + 1, nameLength, StandardCharsets.US_ASCII);
            offset += 1 + nameLength;
        } else if (atyp == 4) {
            if (length < offset + 16 + 2) {
                return null;
            }
            byte[] raw = Arrays.copyOfRange(data, offset, offset + 16);
            try {
                host = InetAddress.getByAddress(raw).getHostAddress();
            } catch (Exception error) {
                return null;
            }
            offset += 16;
        } else {
            return null;
        }
        int port = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
        offset += 2;
        if (port != 53) {
            return null;
        }
        byte[] query = Arrays.copyOfRange(data, offset, length);
        byte[] response = dnsThroughProxy(host, port, query);
        if (response == null) {
            return null;
        }
        byte[] header = Arrays.copyOfRange(data, 0, offset);
        byte[] body = new byte[header.length + response.length];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(response, 0, body, header.length, response.length);
        return new DatagramPacket(body, body.length, packet.getAddress(), packet.getPort());
    }

    /** DNS-запрос по TCP через прокси (RFC 1035: 2 байта длины + сообщение). */
    private byte[] dnsThroughProxy(String host, int port, byte[] query) {
        Socket upstream = null;
        try {
            if (engine != null && engine.isRunning()) {
                upstream = openThroughEngine(host, port);
            } else {
                upstream = openThroughAnyNode(host, port);
            }
            if (upstream == null) {
                return null;
            }
            upstream.setSoTimeout(8000);
            OutputStream out = upstream.getOutputStream();
            out.write(new byte[]{(byte) (query.length >> 8), (byte) (query.length & 0xff)});
            out.write(query);
            out.flush();
            InputStream in = upstream.getInputStream();
            byte[] lengthBytes = Upstream.readFully(in, 2);
            int responseLength = ((lengthBytes[0] & 0xff) << 8) | (lengthBytes[1] & 0xff);
            if (responseLength <= 0 || responseLength > 65535) {
                return null;
            }
            return Upstream.readFully(in, responseLength);
        } catch (Exception error) {
            Log.d(TAG, "DNS через прокси не удался: " + error.getMessage());
            return null;
        } finally {
            closeQuietly(upstream);
        }
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
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // уже закрыто
        }
    }
}
