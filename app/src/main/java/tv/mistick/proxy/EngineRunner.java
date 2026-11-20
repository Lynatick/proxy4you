package tv.mistick.proxy;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Внешнее ядро (sing-box или Xray) как отдельный процесс.
 *
 * Бинарники весят 30–80 МБ, поэтому в APK они не встраиваются: скачиваются при
 * первом запуске под ABI устройства. Дальше ядро поднимает локальный вход, и наш
 * SOCKS5-сервер ходит в него как в обычную ноду.
 */
final class EngineRunner {

    enum Kind {
        SINGBOX("sing-box"),
        XRAY("xray");

        final String id;

        Kind(String id) {
            this.id = id;
        }

        static Kind from(String value) {
            if (value == null) {
                return null;
            }
            for (Kind kind : values()) {
                if (kind.id.equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private static final String TAG = "MiStickProxy";
    private static final String ASSET_DIR = "engines";
    private static final String SB_ARM64 = "https://github.com/SagerNet/sing-box/releases/download/v1.14.4/sing-box-1.14.4-android-arm64.tar.gz";
    private static final String SB_ARM = "https://github.com/SagerNet/sing-box/releases/download/v1.14.4/sing-box-1.14.4-android-arm.tar.gz";
    private static final String XRAY_ARM64 = "https://github.com/XTLS/Xray-core/releases/download/v26.11.9/Xray-android-arm64-v8a.zip";

    private final Context context;
    private Process process;
    private int port;
    private int controllerPort;
    private Kind kind;
    private String logPath;

    EngineRunner(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isRunning() {
        return process != null && process.isAlive();
    }

    int port() {
        return port;
    }

    int controllerPort() {
        return controllerPort;
    }

    Kind kind() {
        return kind;
    }

    String logPath() {
        return logPath;
    }

    /** Запускает ядро из подписки (JSON sing-box) с пачкой `batchIndex`. */
    int start(Kind requested, String subscriptionJson, int batchIndex) throws Exception {
        stop();
        if (requested == null) {
            throw new IllegalArgumentException("ядро не выбрано");
        }
        if (requested == Kind.XRAY && is32BitArm()) {
            throw new IOException("Xray не выпускает сборку под 32-битный ARM — выберите sing-box");
        }

        File binary = ensureBinary(requested);
        port = freePort();
        controllerPort = requested == Kind.SINGBOX ? freePort() : 0;
        String config = requested == Kind.SINGBOX
                ? EngineConfig.singBox(subscriptionJson, port, batchIndex, controllerPort)
                : EngineConfig.xray(subscriptionJson, port, batchIndex);

        File dir = new File(context.getFilesDir(), "engine");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File configFile = new File(dir, requested.id + ".json");
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            out.write(config.getBytes("UTF-8"));
        }
        logPath = new File(dir, requested.id + ".log").getAbsolutePath();

        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "run", "-c", configFile.getAbsolutePath());
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(logPath));
        process = builder.start();
        kind = requested;

        if (!waitForPort(port, 12000)) {
            stop();
            throw new IOException(requested.id + " не поднял вход за 12 секунд (см. " + logPath + ")");
        }
        return port;
    }

    void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        port = 0;
        kind = null;
    }

    // MARK: - Бинарник

    private boolean hasBinary(Kind requested) {
        return binaryFile(requested).exists();
    }

    private File binaryFile(Kind requested) {
        File dir = new File(context.getFilesDir(), "bin");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, requested.id);
    }

    private File ensureBinary(Kind requested) throws Exception {
        File file = binaryFile(requested);
        if (file.exists() && file.canExecute()) {
            return file;
        }

        // Ядра встроены в APK: сначала распаковываем из assets, сеть — только запасной путь.
        if (extractFromAssets(requested)) {
            return file;
        }

        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        boolean arm64 = abi.contains("arm64");
        String url;
        if (requested == Kind.SINGBOX) {
            url = arm64 ? SB_ARM64 : SB_ARM;
        } else {
            if (!arm64) {
                throw new IOException("Xray не выпускает сборку под 32-битный ARM — выберите sing-box");
            }
            url = XRAY_ARM64;
        }

        File archive = new File(context.getFilesDir(), requested.id + (url.endsWith(".zip") ? ".zip" : ".tar.gz"));
        download(url, archive);
        File dir = new File(context.getFilesDir(), "bin");
        if (url.endsWith(".zip")) {
            unzip(archive, dir);
        } else {
            untar(archive, dir, true);
        }
        File extracted = new File(dir, requested.id);
        if (!extracted.exists()) {
            throw new IOException("в архиве нет " + requested.id);
        }
        extracted.setExecutable(true, false);
        //noinspection ResultOfMethodCallIgnored
        archive.delete();
        return extracted;
    }

    /**
     * Распаковка встроенного архива из assets. false — архива нет или он битый.
     *
     * AGP при упаковке распаковывает `.tar.gz` в `.tar`, поэтому пробуем оба имени:
     * сначала `.tar` (без -z), затем `.tar.gz` (с -z).
     */
    private boolean extractFromAssets(Kind requested) {
        if (requested == Kind.XRAY) {
            return extractAsset("xray-android-arm64.zip", requested, false);
        }
        String base = is32BitArm() ? "sing-box-android-arm" : "sing-box-android-arm64";
        return extractAsset(base + ".tar", requested, false)
                || extractAsset(base + ".tar.gz", requested, true);
    }

    private boolean extractAsset(String name, Kind requested, boolean gzipped) {
        File archive = new File(context.getFilesDir(), name);
        try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + name);
             FileOutputStream out = new FileOutputStream(archive)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception error) {
            return false;
        }

        File dir = new File(context.getFilesDir(), "bin");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try {
            if (name.endsWith(".zip")) {
                unzip(archive, dir);
            } else {
                untar(archive, dir, gzipped);
            }
            File binary = new File(dir, requested.id);
            if (!binary.exists()) {
                return false;
            }
            binary.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            return true;
        } catch (Exception error) {
            Log.w(TAG, "не удалось распаковать " + name, error);
            return false;
        }
    }

    private boolean is32BitArm() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        return abi.startsWith("armeabi") || (abi.startsWith("arm") && !abi.contains("64"));
    }

    private void download(String urlText, File target) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlText).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(300000);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void untar(File archive, File dir, boolean gzipped) throws Exception {
        String flag = gzipped ? "-xzf" : "-xf";
        Process process = new ProcessBuilder("/system/bin/tar", flag, archive.getAbsolutePath(),
                "-C", dir.getAbsolutePath()).start();
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("tar завершился с кодом " + code);
        }
        // внутри архива есть подкаталог (sing-box-1.14.4-android-arm64/sing-box)
        File nested = findFile(dir, "sing-box");
        File target = new File(dir, "sing-box");
        if (nested != null && !nested.getAbsolutePath().equals(target.getAbsolutePath())) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            //noinspection ResultOfMethodCallIgnored
            nested.renameTo(target);
        }
    }

    /** Рекурсивный поиск файла по имени. */
    private File findFile(File dir, String name) {
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isFile() && child.getName().equals(name)) {
                return child;
            }
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findFile(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void unzip(File archive, File dir) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new java.io.FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/")) {
                    continue;
                }
                String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                if (!base.equals("xray")) {
                    continue;
                }
                File target = new File(dir, "xray");
                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                target.setExecutable(true, false);
                return;
            }
        }
        throw new IOException("в архиве нет xray");
    }

    // MARK: - Порт

    private boolean waitForPort(int targetPort, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", targetPort), 500);
                return true;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        Log.w(TAG, "ядро не подняло порт " + targetPort);
        return false;
    }

    private int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException error) {
            return 20910;
        }
    }
}
