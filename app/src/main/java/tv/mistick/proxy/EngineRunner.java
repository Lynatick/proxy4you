package tv.mistick.proxy;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Внешнее ядро (sing-box или Xray) как отдельный процесс.
 *
 * Бинарники лежат в jniLibs и попадают в `nativeLibraryDir` при установке.
 * Это единственный путь, откуда Android 10+ разрешает исполнять файлы: из
 * `filesDir` запуск блокируется (`EACCES`).
 */
final class EngineRunner {

    enum Kind {
        SINGBOX("sing-box", "libsingbox.so"),
        XRAY("xray", "libxray.so");

        final String id;
        final String library;

        Kind(String id, String library) {
            this.id = id;
            this.library = library;
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

    /** Запускает ядро на готовом списке outbounds с пачкой `batchIndex`. */
    int start(Kind requested, java.util.List<org.json.JSONObject> nodes, int batchIndex) throws Exception {
        stop();
        if (requested == null) {
            throw new IllegalArgumentException("ядро не выбрано");
        }

        File binary = binaryFile(requested);
        if (!binary.exists()) {
            throw new IOException("встроенное ядро не найдено: " + binary.getAbsolutePath());
        }
        if (!binary.canExecute() && !binary.setExecutable(true)) {
            throw new IOException("ядро не исполняемое: " + binary.getAbsolutePath());
        }

        port = freePort();
        controllerPort = requested == Kind.SINGBOX ? freePort() : 0;
        String config = requested == Kind.SINGBOX
                ? EngineConfig.singBox(nodes, port, batchIndex, controllerPort)
                : EngineConfig.xray(nodes, port, batchIndex);

        File dir = new File(context.getFilesDir(), "engine");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File configFile = new File(dir, requested.id + ".json");
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            out.write(config.getBytes("UTF-8"));
        }
        logPath = new File(dir, requested.id + ".log").getAbsolutePath();

        ProcessBuilder builder = new ProcessBuilder(
                binary.getAbsolutePath(), "run", "-c", configFile.getAbsolutePath());
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(logPath));
        builder.directory(dir);
        process = builder.start();
        kind = requested;

        if (!waitForPort(port, 20000)) {
            stop();
            throw new IOException(requested.id + " не поднял вход (см. " + logPath + ")");
        }
        return port;
    }

    void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        port = 0;
        controllerPort = 0;
        kind = null;
    }

    /** Файл ядра из nativeLibraryDir — единственное место, разрешённое для exec. */
    private File binaryFile(Kind requested) {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        return new File(nativeDir, requested.library);
    }

    private boolean waitForPort(int targetPort, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                Log.w(TAG, "ядро завершилось до открытия порта");
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
