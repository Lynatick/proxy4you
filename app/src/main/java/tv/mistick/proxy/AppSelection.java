package tv.mistick.proxy;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Какие приложения пускать через VPN.
 *
 * Список непустой → VpnService.Builder.addAllowedApplication для каждого пакета:
 * весь остальной трафик телевизора идёт напрямую, как будто прокси нет.
 */
final class AppSelection {

    private static final String PREFS = "proxy";
    private static final String KEY = "apps";
    private static final String INITIALIZED = "apps_initialized";

    private AppSelection() {
    }

    static Set<String> load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(INITIALIZED, false)) {
            return defaultSelection(context);
        }
        String raw = preferences.getString(KEY, "");
        Set<String> result = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            Collections.addAll(result, raw.split(","));
        }
        return result;
    }

    static void save(Context context, Set<String> packages) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString(KEY, String.join(",", packages));
        editor.putBoolean(INITIALIZED, true);
        editor.apply();
    }

    /** По умолчанию — приложения YouTube, если они установлены. */
    static Set<String> defaultSelection(Context context) {
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : new String[]{
                "com.google.android.youtube.tv",
                "com.google.android.youtube"
        }) {
            if (isInstalled(context, candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    /** Все приложения с иконкой в лаунчере — их и показывает экран выбора. */
    static List<ResolveInfo> launchableApps(Context context) {
        PackageManager manager = context.getPackageManager();
        List<ResolveInfo> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent normal = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        for (ResolveInfo info : manager.queryIntentActivities(leanback, 0)) {
            if (seen.add(info.activityInfo.packageName)) {
                result.add(info);
            }
        }
        for (ResolveInfo info : manager.queryIntentActivities(normal, 0)) {
            if (seen.add(info.activityInfo.packageName)) {
                result.add(info);
            }
        }
        java.util.Collections.sort(result, (a, b) -> a.loadLabel(manager).toString()
                .compareToIgnoreCase(b.loadLabel(manager).toString()));
        return result;
    }
}
