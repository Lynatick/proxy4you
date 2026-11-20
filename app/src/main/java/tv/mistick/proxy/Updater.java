package tv.mistick.proxy;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Скачивание APK обновления и передача его системному установщику.
 *
 * Проверенный файл отдаётся через собственный ContentProvider: с Android 7
 * передавать `file://` в другое приложение нельзя.
 */
final class Updater {

    static final String AUTHORITY = "tv.mistick.proxy.apk";

    /** Колбэк прогресса: процент 0…100 или -1, если размер неизвестен. */
    interface ProgressListener {
        void onProgress(int percent);
    }

    private Updater() {
    }

    static File download(Context context, String urlText, ProgressListener listener) throws Exception {
        File dir = new File(context.getCacheDir(), "update");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File apk = new File(dir, "update.apk");
        //noinspection ResultOfMethodCallIgnored
        apk.delete();

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlText).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(300000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "YouTubeProxyTV");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            int total = connection.getContentLength();
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buffer = new byte[65536];
                long received = 0;
                int lastPercent = -1;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    received += read;
                    if (total > 0 && listener != null) {
                        int percent = (int) (received * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(percent);
                        }
                    }
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        if (apk.length() == 0) {
            throw new Exception("скачался пустой файл");
        }
        return apk;
    }

    static Intent installIntent(File apk) {
        Uri uri = Uri.parse("content://" + AUTHORITY + "/" + apk.getName());
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

}
