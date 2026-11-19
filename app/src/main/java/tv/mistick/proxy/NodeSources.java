package tv.mistick.proxy;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Публичные списки нод: те же шесть источников, что в macOS-версии.
 *
 * Скачанные ноды сохраняются отдельным файлом и объединяются с подпиской при
 * загрузке; проверка живучести — как и раньше, при подключении (failover).
 */
final class NodeSources {

    static final class Source {
        final String name;
        final String url;
        final boolean socks;

        Source(String name, String url, boolean socks) {
            this.name = name;
            this.url = url;
            this.socks = socks;
        }
    }

    static final Source[] ALL = {
            new Source("TheSpeedX · http",
                    "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt", false),
            new Source("TheSpeedX · socks5",
                    "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt", true),
            new Source("monosans · http",
                    "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt", false),
            new Source("monosans · socks5",
                    "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt", true),
            new Source("proxifly · http",
                    "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt", false),
            new Source("hookzof · socks5",
                    "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt", true),
    };

    private static final String FILE = "public_nodes.txt";

    private NodeSources() {
    }

    /** Скачивает все источники. Возвращает объединённый список без повторов. */
    static List<NodeStore.Node> fetchAll(Context context) {
        Map<String, NodeStore.Node> merged = new LinkedHashMap<>();
        for (Source source : ALL) {
            try {
                String text = download(source.url);
                for (NodeStore.Node node : parse(text, source.socks)) {
                    merged.putIfAbsent(node.host + ":" + node.port + ":" + node.socks5, node);
                }
            } catch (Exception ignored) {
                // один источник недоступен — остальные всё равно используем
            }
        }
        List<NodeStore.Node> nodes = new ArrayList<>(merged.values());
        save(context, nodes);
        return nodes;
    }

    static List<NodeStore.Node> parse(String text, boolean socks) {
        List<NodeStore.Node> nodes = new ArrayList<>();
        for (String rawLine : text.split("[\\r\\n]+")) {
            String line = rawLine.trim();
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).trim();
            }
            if (line.isEmpty() || !line.contains(":")) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            String host = line.substring(0, colon).trim();
            String portText = line.substring(colon + 1).trim();
            if (host.isEmpty() || host.contains(" ")) {
                continue;
            }
            try {
                int port = Integer.parseInt(portText);
                if (port > 0 && port <= 65535) {
                    nodes.add(new NodeStore.Node(host, port, socks));
                }
            } catch (NumberFormatException ignored) {
                // не адрес — пропускаем
            }
        }
        return nodes;
    }

    static void save(Context context, List<NodeStore.Node> nodes) {
        StringBuilder builder = new StringBuilder();
        for (NodeStore.Node node : nodes) {
            builder.append(node.socks5 ? "socks5://" : "http://")
                    .append(node.host).append(':').append(node.port).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE))) {
            out.write(builder.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
            // без файла останется предыдущий список
        }
    }

    private static String download(String urlText) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlText).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[32768];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toString("UTF-8");
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
