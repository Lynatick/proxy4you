package tv.mistick.proxy;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Список нод, экспортированный из macOS-приложения (assets/nodes.txt). */
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
        List<Node> nodes = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("nodes.txt"), "UTF-8"))) {
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
                        nodes.add(new Node(host, port, socks));
                    }
                } catch (NumberFormatException ignored) {
                    // строка не нашего формата — пропускаем
                }
            }
        } catch (Exception ignored) {
            // нет ресурса — вернём пустой список, интерфейс это покажет
        }
        return nodes;
    }
}
