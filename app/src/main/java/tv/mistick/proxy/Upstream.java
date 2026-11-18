package tv.mistick.proxy;

import android.net.VpnService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Соединение к удалённой ноде. Сокет обязательно проходит через VpnService.protect(). */
final class Upstream {

    private Upstream() {
    }

    static Socket connect(VpnService vpn, NodeStore.Node node, String host, int port, int timeoutMs)
            throws IOException {
        Socket socket = new Socket();
        if (vpn != null && !vpn.protect(socket)) {
            socket.close();
            throw new IOException("VpnService.protect() отказал");
        }
        socket.connect(new InetSocketAddress(node.host, node.port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        if (node.socks5) {
            socks5(socket, host, port);
        } else {
            httpConnect(socket, host, port);
        }
        socket.setSoTimeout(0);
        return socket;
    }

    private static void httpConnect(Socket socket, String host, int port) throws IOException {
        String authority = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        String request = "CONNECT " + authority + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "Proxy-Connection: keep-alive\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("прокси закрыл соединение");
            }
            head.append((char) b);
            int length = head.length();
            if (length >= 4
                    && head.charAt(length - 4) == '\r' && head.charAt(length - 3) == '\n'
                    && head.charAt(length - 2) == '\r' && head.charAt(length - 1) == '\n') {
                break;
            }
            if (length > 8192) {
                throw new IOException("слишком длинный ответ прокси");
            }
        }

        String statusLine = head.toString().split("\r\n", 2)[0];
        if (!statusLine.contains(" 200")) {
            throw new IOException("CONNECT отклонён: " + statusLine);
        }
    }

    private static void socks5(Socket socket, String host, int port) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        out.write(new byte[]{5, 1, 0});
        out.flush();
        byte[] greeting = readFully(in, 2);
        if (greeting[0] != 5 || greeting[1] != 0) {
            throw new IOException("SOCKS5: метод не принят");
        }

        byte[] address;
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] parts = host.split("\\.");
            address = new byte[5];
            address[0] = 1;
            for (int i = 0; i < 4; i++) {
                address[1 + i] = (byte) Integer.parseInt(parts[i]);
            }
        } else {
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            if (name.length > 255) {
                throw new IOException("SOCKS5: слишком длинное имя");
            }
            address = new byte[2 + name.length];
            address[0] = 3;
            address[1] = (byte) name.length;
            System.arraycopy(name, 0, address, 2, name.length);
        }

        byte[] request = new byte[3 + address.length + 2];
        request[0] = 5;
        request[1] = 1;
        request[2] = 0;
        System.arraycopy(address, 0, request, 3, address.length);
        request[3 + address.length] = (byte) (port >> 8);
        request[4 + address.length] = (byte) port;
        out.write(request);
        out.flush();

        byte[] reply = readFully(in, 4);
        if (reply[1] != 0) {
            throw new IOException("SOCKS5: код ошибки " + reply[1]);
        }
        int atyp = reply[3];
        if (atyp == 1) {
            readFully(in, 6);
        } else if (atyp == 4) {
            readFully(in, 18);
        } else if (atyp == 3) {
            int length = readFully(in, 1)[0] & 0xff;
            readFully(in, length + 2);
        } else {
            throw new IOException("SOCKS5: неизвестный тип адреса");
        }
    }

    static byte[] readFully(InputStream in, int count) throws IOException {
        byte[] buffer = new byte[count];
        int filled = 0;
        while (filled < count) {
            int read = in.read(buffer, filled, count - filled);
            if (read < 0) {
                throw new IOException("соединение закрыто");
            }
            filled += read;
        }
        return buffer;
    }
}
