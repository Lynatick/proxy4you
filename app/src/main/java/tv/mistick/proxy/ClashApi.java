package tv.mistick.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Опрос clash_api sing-box: текущая нода и задержки нод пачки. */
final class ClashApi {

    static final class NodeInfo {
        final String name;
        final int delay;

        NodeInfo(String name, int delay) {
            this.name = name;
            this.delay = delay;
        }
    }

    private ClashApi() {
    }

    /** «n00012|1234»: текущая нода и задержка (мс), или null. */
    static String current(int controllerPort) {
        try {
            JSONObject object = new JSONObject(get("http://127.0.0.1:" + controllerPort + "/proxies/auto"));
            String now = object.optString("now", "");
            if (now.isEmpty()) {
                return null;
            }
            int delay = -1;
            JSONArray history = object.optJSONArray("history");
            if (history != null && history.length() > 0) {
                JSONObject last = history.optJSONObject(history.length() - 1);
                if (last != null) {
                    delay = last.optInt("delay", -1);
                }
            }
            return now + "|" + delay;
        } catch (Exception error) {
            return null;
        }
    }

    /** Ноды пачки, у которых уже измерена задержка. */
    static List<NodeInfo> nodes(int controllerPort, int limit) {
        List<NodeInfo> result = new ArrayList<>();
        try {
            JSONObject object = new JSONObject(get("http://127.0.0.1:" + controllerPort + "/proxies"));
            JSONArray names = object.names();
            if (names == null) {
                return result;
            }
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i);
                JSONObject proxy = object.optJSONObject(name);
                if (proxy == null) {
                    continue;
                }
                JSONArray history = proxy.optJSONArray("history");
                if (history == null || history.length() == 0) {
                    continue;
                }
                JSONObject last = history.optJSONObject(history.length() - 1);
                int delay = last == null ? -1 : last.optInt("delay", -1);
                if (delay > 0) {
                    result.add(new NodeInfo(name, delay));
                }
            }
        } catch (Exception ignored) {
            // API не ответил — покажем то, что есть
        }
        result.sort((left, right) -> Integer.compare(left.delay, right.delay));
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    private static String get(String urlText) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlText).openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
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
