package tv.mistick.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Какие сервисы направлять через прокси, а какие — напрямую.
 *
 * Правила совпадают по смыслу с macOS-версией: у каждого сервиса свой набор
 * доменов, поддомены наследуются. Настройка хранится в SharedPreferences.
 */
final class RoutePolicy {

    private static final String PREFS = "route";
    private static final String KEY_GROUPS = "groups";
    private static final String KEY_EVERYTHING = "everything";

    enum Group {
        YOUTUBE("YouTube", new String[]{
                "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com",
                "youtube-nocookie.com", "youtubei.googleapis.com", "youtube.googleapis.com",
                "yt3.ggpht.com", "ggpht.com", "wide-youtube.l.google.com",
        }),
        CHATGPT("ChatGPT", new String[]{
                "chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com",
                "auth0.openai.com", "cdn.oaistatic.com", "chatgpt.livekit.cloud",
        }),
        TELEGRAM("Telegram", new String[]{
                "telegram.org", "t.me", "telegram.me", "telegram.dog", "api.telegram.org",
                "tdesktop.com", "telegram-cdn.org", "cdn-telegram.org", "telesco.pe", "tg.dev",
        }),
        WHATSAPP("WhatsApp", new String[]{
                "whatsapp.com", "whatsapp.net", "wa.me", "waa.tw", "web.whatsapp.com",
                "static.whatsapp.net", "mmg.whatsapp.net", "graph.whatsapp.com",
        }),
        GITHUB("GitHub", new String[]{
                "github.com", "githubusercontent.com", "githubassets.com", "githubapp.com",
                "ghcr.io", "api.github.com", "gist.github.com", "raw.githubusercontent.com",
                "objects.githubusercontent.com", "codeload.github.com",
        });

        final String title;
        final String[] domains;

        Group(String title, String[] domains) {
            this.title = title;
            this.domains = domains;
        }
    }

    private RoutePolicy() {
    }

    static boolean everything(Context context) {
        return prefs(context).getBoolean(KEY_EVERYTHING, false);
    }

    static void setEverything(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_EVERYTHING, value).apply();
    }

    /** Выбранные сервисы; по умолчанию — только YouTube. */
    static Set<Group> load(Context context) {
        Set<Group> result = new LinkedHashSet<>();
        String raw = prefs(context).getString(KEY_GROUPS, null);
        if (raw == null) {
            result.add(Group.YOUTUBE);
            return result;
        }
        for (String part : raw.split(",")) {
            for (Group group : Group.values()) {
                if (group.name().equals(part.trim())) {
                    result.add(group);
                }
            }
        }
        return result;
    }

    static void save(Context context, Set<Group> groups) {
        StringBuilder builder = new StringBuilder();
        for (Group group : Group.values()) {
            if (groups.contains(group)) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(group.name());
            }
        }
        prefs(context).edit().putString(KEY_GROUPS, builder.toString()).apply();
    }

    /** Идёт ли трафик к `host` через прокси. */
    static boolean requiresProxy(Context context, String host) {
        if (everything(context)) {
            return true;
        }
        String normalized = normalize(host);
        if (normalized.isEmpty()) {
            return false;
        }
        for (Group group : load(context)) {
            for (String domain : group.domains) {
                if (normalized.equals(domain) || normalized.endsWith("." + domain)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String normalize(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        int colon = value.lastIndexOf(':');
        if (colon > 0 && !value.startsWith("[")) {
            String candidate = value.substring(0, colon);
            if (!candidate.contains(":")) {
                value = candidate;
            }
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
