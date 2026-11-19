package tv.mistick.proxy;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Подписка: скачивание списка share-ссылок и разбор в ноды.
 *
 * Поддерживаются `socks5://` и `http(s)://` — только их умеет туннель этого
 * приложения. Остальные схемы (vless/vmess/trojan/ss) пропускаются: для них
 * нужны ядра sing-box/Xray, которых в сборке для ТВ пока нет.
 */
final class Subscription {

    private static final String NODES_FILE = "nodes.txt";
    private static final String URL_FILE = "subscription.txt";

    private Subscription() {
    }

    /** Скачивает подписку, разбирает ноды и сохраняет их для VPN-сервиса. */
    static List<NodeStore.Node> refresh(Context context, String url) throws IOException {
        String text = download(url);
        List<NodeStore.Node> nodes = parse(text);
        if (nodes.isEmpty()) {
            throw new IOException("в подписке нет socks5/http-нод");
        }
        saveNodes(context, nodes);
        writeText(new File(context.getFilesDir(), URL_FILE), url);
        return nodes;
    }

    static String savedUrl(Context context) {
        try {
            File file = new File(context.getFilesDir(), URL_FILE);
            if (!file.exists()) {
                return "";
            }
            return new String(readAll(file), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return "";
        }
    }

    private static String download(String urlText) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText.trim());
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "YouTubeProxyTV/1.1");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = connection.getInputStream();
            return new String(readAll(in), StandardCharsets.UTF_8);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static List<NodeStore.Node> parse(String text) {
        String content = text;
        if (!content.contains("://")) {
            String decoded = decodeBase64(content);
            if (decoded != null) {
                content = decoded;
            }
        }

        List<NodeStore.Node> nodes = new ArrayList<>();
        for (String rawLine : content.split("[\\r\\n]+")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("://")) {
                continue;
            }
            boolean socks = line.startsWith("socks5://") || line.startsWith("socks://");
            boolean http = line.startsWith("http://") || line.startsWith("https://");
            if (!socks && !http) {
                continue;
            }
            String rest = line.substring(line.indexOf("://") + 3);
            int hash = rest.indexOf('#');
            if (hash >= 0) {
                rest = rest.substring(0, hash);
            }
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            int at = rest.lastIndexOf('@');
            if (at >= 0) {
                rest = rest.substring(at + 1);
            }
            int colon = rest.lastIndexOf(':');
            if (colon <= 0 || colon == rest.length() - 1) {
                continue;
            }
            try {
                String host = rest.substring(0, colon);
                int port = Integer.parseInt(rest.substring(colon + 1));
                if (port > 0 && port <= 65535) {
                    nodes.add(new NodeStore.Node(host, port, socks));
                }
            } catch (NumberFormatException ignored) {
                // не наш формат — пропускаем
            }
        }
        return nodes;
    }

    private static String decodeBase64(String text) {
        try {
            byte[] raw = Base64.decode(text.replaceAll("\\s", ""), Base64.DEFAULT);
            String decoded = new String(raw, StandardCharsets.UTF_8);
            return decoded.contains("://") ? decoded : null;
        } catch (Exception error) {
            return null;
        }
    }

    private static void saveNodes(Context context, List<NodeStore.Node> nodes) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (NodeStore.Node node : nodes) {
            builder.append(node.socks5 ? "socks5://" : "http://")
                    .append(node.host).append(':').append(node.port).append('\n');
        }
        writeText(new File(context.getFilesDir(), NODES_FILE), builder.toString());
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }
}
