package tv.mistick.proxy;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Список нод для пула.
 *
 * Источники по приоритету: подписка (filesDir/nodes.txt), автосбор публичных
 * списков (filesDir/public_nodes.txt) и встроенный assets/nodes.txt. Повторы
 * по адресу и протоколу убираются.
 */
final class NodeStore {

    static final class Node {
        final String host;
        final int port;
        final boolean socks5;

        Node(String host, int port, boolean socks5) {
            this.host = host;
            this.port = port;
            this.socks5 = socks5;
        }

        String label() {
            return (socks5 ? "SOCKS5  " : "HTTP    ") + host + ":" + port;
        }
    }

    private NodeStore() {
    }

    static List<Node> load(Context context) {
        Map<String, Node> merged = new LinkedHashMap<>();
        readFile(new File(context.getFilesDir(), "nodes.txt"), merged);
        readFile(new File(context.getFilesDir(), "public_nodes.txt"), merged);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("nodes.txt"), "UTF-8"))) {
            readLines(reader, merged);
        } catch (Exception ignored) {
            // нет ресурса — останутся уже собранные ноды
        }
        return new ArrayList<>(merged.values());
    }

    private static void readFile(File file, Map<String, Node> merged) {
        if (!file.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            readLines(reader, merged);
        } catch (Exception ignored) {
            // файл повреждён — пропускаем
        }
    }

    private static void readLines(BufferedReader reader, Map<String, Node> merged) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("://")) {
                continue;
            }
            boolean socks = line.startsWith("socks5://") || line.startsWith("socks://");
            String rest = line.substring(line.indexOf("://") + 3);
            int colon = rest.lastIndexOf(':');
            if (colon <= 0 || colon == rest.length() - 1) {
                continue;
            }
            try {
                String host = rest.substring(0, colon);
                int port = Integer.parseInt(rest.substring(colon + 1));
                if (port > 0 && port <= 65535) {
                    merged.putIfAbsent(host + ":" + port + ":" + socks, new Node(host, port, socks));
                }
            } catch (NumberFormatException ignored) {
                // строка не нашего формата — пропускаем
            }
        }
    }
}
