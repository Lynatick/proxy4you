package tv.mistick.proxy;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
        return process != null && isAlive(process);
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

        File dir = new File(context.getFilesDir(), "engine");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File configFile = new File(dir, requested.id + ".json");

        // nodes — уже готовая пачка: повторно её не режем (иначе пачка 2+ пустая).
        // Битые outbounds (например, неизвестный uTLS-отпечаток) отбраковываем по check.
        java.util.List<org.json.JSONObject> current = new java.util.ArrayList<>(nodes);
        String config = "";
        for (int attempt = 0; attempt < 30; attempt++) {
            config = requested == Kind.SINGBOX
                    ? EngineConfig.singBox(current, port, 0, controllerPort)
                    : EngineConfig.xray(current, port, 0);
            try (FileOutputStream out = new FileOutputStream(configFile)) {
                out.write(config.getBytes("UTF-8"));
            }
            String check = requested == Kind.SINGBOX
                    ? runCapture(binary, 6000, "check", "-c", configFile.getAbsolutePath())
                    : runCapture(binary, 8000, "run", "-test", "-c", configFile.getAbsolutePath());
            int bad = outboundIndex(check);
            if (bad < 0) {
                bad = outboundIndexByTag(current, check);
            }
            if (bad < 0) {
                break;
            }
            if (bad >= current.size()) {
                break;
            }
            Log.w(TAG, "outbound[" + bad + "] невалиден, отбрасываю: " + check.trim());
            current.remove(bad);
            if (current.isEmpty()) {
                throw new IOException("в подписке нет валидных outbounds");
            }
        }
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            out.write(config.getBytes("UTF-8"));
        }
        logPath = new File(dir, requested.id + ".log").getAbsolutePath();
        Log.i(TAG, "конфиг " + config.length() + " символов, начало: "
                + config.substring(0, Math.min(160, config.length())));

        ProcessBuilder builder = new ProcessBuilder(
                binary.getAbsolutePath(), "run", "-c", configFile.getAbsolutePath());
        builder.redirectErrorStream(true);
        // Вывод всегда читаем сами: redirectOutput(File) на Android ненадёжен —
        // дочерний процесс повисает, не дойдя до открытия входа.
        Log.i(TAG, "ядро: " + binary.getAbsolutePath() + " (" + binary.length()
                + " байт, exec=" + binary.canExecute() + "), порт " + port);
        process = builder.start();
        Log.i(TAG, "ядро запущено, pid=" + pid(process));

        final InputStream stream = process.getInputStream();
        final FileOutputStream logOut = new FileOutputStream(logPath);
        Log.i(TAG, "поток лога запускается, файл " + logPath);
        Thread pump = new Thread(() -> {
            byte[] buffer = new byte[8192];
            long total = 0;
            try (InputStream in = stream; FileOutputStream out = logOut) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                    total += read;
                }
                Log.i(TAG, "поток лога завершён, прочитано " + total + " байт");
            } catch (Throwable error) {
                Log.w(TAG, "поток лога: " + error);
            }
        }, "engine-log");
        pump.setDaemon(true);
        pump.start();
        kind = requested;

        if (!waitForPort(port, 20000)) {
            String tail = readLogTail(logPath, 4000);
            String version = runAndCapture(binary, "version");
            String check = requested == Kind.SINGBOX
                    ? runAndCapture(binary, "check", "-c", configFile.getAbsolutePath())
                    : runAndCapture(binary, "run", "-test", "-c", configFile.getAbsolutePath());
            Log.e(TAG, "диагностика: version=[" + version + "] check=[" + check + "]");
            stop();
            throw new IOException(requested.id + " не поднял вход; version=[" + version
                    + "] check=[" + check + "] лог: [" + tail + "]");
        }
        return port;
    }

    /** Номер битого outbound из вывода check (-1 — конфиг валиден). */
    private static int outboundIndex(String output) {
        if (output == null || output.isEmpty()) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("outbound\\[(\\d+)\\]").matcher(output);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /** Индекс outbound по тегу из текста ошибки (-1, если тег не найден). */
    private static int outboundIndexByTag(java.util.List<org.json.JSONObject> nodes, String output) {
        if (output == null || output.isEmpty()) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("with tag (.+?)(?:\\s*>|\\s*$)", java.util.regex.Pattern.MULTILINE)
                .matcher(output);
        if (!matcher.find()) {
            return -1;
        }
        String tag = matcher.group(1).trim();
        for (int i = 0; i < nodes.size(); i++) {
            if (tag.equals(nodes.get(i).optString("tag"))) {
                return i;
            }
        }
        return -1;
    }

    /** Запускает ядро с аргументами и возвращает его вывод (для диагностики). */
    private String runCapture(File binary, int timeoutMs, String... args) {
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(binary.getAbsolutePath());
            java.util.Collections.addAll(command, args);
            Process probe = new ProcessBuilder(command).redirectErrorStream(true).start();
            java.io.InputStream in = probe.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int available = in.available();
                if (available > 0) {
                    int read = in.read(buffer, 0, Math.min(buffer.length, available));
                    if (read > 0) {
                        out.write(buffer, 0, read);
                    }
                } else if (!isAlive(probe)) {
                    break;
                } else {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            probe.destroy();
            return out.toString("UTF-8").trim();
        } catch (Exception error) {
            return "ошибка запуска: " + error.getMessage();
        }
    }

    private String runAndCapture(File binary, String... args) {
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(binary.getAbsolutePath());
            java.util.Collections.addAll(command, args);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process probe = builder.start();
            java.io.InputStream in = probe.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline && out.size() < 4096) {
                int available = in.available();
                if (available > 0) {
                    int read = in.read(buffer, 0, Math.min(buffer.length, available));
                    if (read > 0) {
                        out.write(buffer, 0, read);
                    }
                } else {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            probe.destroy();
            return out.toString("UTF-8").trim();
        } catch (Exception error) {
            return "ошибка запуска: " + error.getMessage();
        }
    }

    /** PID процесса через reflection: Process.pid() есть только с API 26. */
    private static String pid(Process process) {
        try {
            java.lang.reflect.Method method = Process.class.getMethod("pid");
            return String.valueOf(method.invoke(process));
        } catch (Exception ignored) {
            return "?";
        }
    }

    /** Последние строки лога ядра — чтобы причина отказа была видна без доступа к файлу. */
    private static String readLogTail(String path, int maxChars) {
        java.io.File file = new java.io.File(path);
        if (!file.exists()) {
            return "лог пуст";
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long length = raf.length();
            long from = Math.max(0, length - maxChars);
            raf.seek(from);
            byte[] buffer = new byte[(int) (length - from)];
            raf.readFully(buffer);
            String text = new String(buffer, "UTF-8").trim();
            return text.isEmpty() ? "лог пуст" : text;
        } catch (Exception error) {
            return "лог недоступен: " + error.getMessage();
        }
    }

    /** Process.isAlive() требует API 26: определяем живость по exitValue(). */
    private static boolean isAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
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
            if (!isAlive(process)) {
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
