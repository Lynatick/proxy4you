package tv.mistick.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Проверка обновлений по GitHub Releases.
 *
 * Берём последний релиз, версию из тега (`v1.8.4` → `1.8.4`) и ссылку на `.apk`.
 * Сравнение версий — по числовым сегментам, чтобы `1.10` было новее `1.9`.
 */
final class UpdateChecker {

    static final String RELEASE_URL = "https://api.github.com/repos/Lynatick/proxy4you/releases/latest";

    static final class Release {
        final String version;
        final String apkUrl;

        Release(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
        }
    }

    private UpdateChecker() {
    }

    static Release latest() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASE_URL).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "YouTubeProxyTV");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("GitHub ответил HTTP " + code);
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                body = buffer.toString("UTF-8");
            }

            JSONObject object = new JSONObject(body);
            String tag = object.optString("tag_name", "");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (version.isEmpty()) {
                throw new Exception("в релизе нет версии");
            }

            String apkUrl = null;
            JSONArray assets = object.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name", "");
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            if (apkUrl == null || apkUrl.isEmpty()) {
                throw new Exception("в релизе нет APK");
            }
            return new Release(version, apkUrl);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** true, если `latest` новее `current` (сравнение по числовым сегментам). */
    static boolean isNewer(String latest, String current) {
        int[] left = parse(latest);
        int[] right = parse(current);
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String[] parts = version == null ? new String[0] : version.trim().split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException error) {
                result[i] = 0;
            }
        }
        return result;
    }
}
