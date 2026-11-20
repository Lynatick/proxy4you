package tv.mistick.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * VPN-сервис: TUN → hev-socks5-tunnel → локальный SOCKS5 → нода.
 *
 * Ноды живут в пуле с состоянием. Периодически текущая нода проверяется настоящим
 * TLS-рукопожатием с YouTube; при отказе сервис сам переключается на следующую
 * рабочую и обновляет статус, ничего не требуя от пользователя.
 */
public final class ProxyVpnService extends VpnService {

    private static final String TAG = "MiStickProxy";
    private static final String CHANNEL_ID = "proxy";
    private static final long HEALTH_INTERVAL_MS = 45_000L;

    static volatile boolean running = false;
    static volatile String status = "Выключено";
    /** Порт локального SOCKS5, пока VPN работает (0 — выключен). */
    static volatile int socksPort = 0;
    /** Текущая пачка подписки и сколько их всего (для интерфейса). */
    static volatile int batchIndex = 0;
    /** Порт clash_api sing-box (0 — нет) и идентификатор запущенного ядра. */
    static volatile int controllerPort = 0;
    static volatile String engineKindId = null;
    static volatile int batchCount = 1;
    private static volatile ProxyVpnService instance;

    private final Handler healthHandler = new Handler(Looper.getMainLooper());

    private ParcelFileDescriptor tun;
    private Socks5Server socks;
    private NodePool pool;
    private EngineRunner engine;
    private String subscriptionText;
    private EngineRunner.Kind engineKind;
    private int appliedApps;
    private int switchCount;

    private final Runnable healthTask = new Runnable() {
        @Override
        public void run() {
            if (!running || pool == null) {
                return;
            }
            final NodePool.Entry active = pool.current();
            new Thread(() -> {
                boolean switched = false;
                if (active != null) {
                    int latency = Health.probe(ProxyVpnService.this, active.node, 8000);
                    if (latency >= 0) {
                        pool.recordSuccess(active, latency);
                    } else {
                        pool.recordFailure(active);
                        switched = true;
                    }
                } else {
                    switched = true;
                }

                if (switched) {
                    for (NodePool.Entry candidate : pool.candidates(3)) {
                        int latency = Health.probe(ProxyVpnService.this, candidate.node, 8000);
                        if (latency >= 0) {
                            pool.recordSuccess(candidate, latency);
                            switchCount++;
                            Log.i(TAG, "переключение на " + candidate.label() + " (" + latency + " мс)");
                            break;
                        }
                        pool.recordFailure(candidate);
                    }
                }

                healthHandler.post(() -> {
                    if (!running) {
                        return;
                    }
                    publishStatus();
                    healthHandler.postDelayed(healthTask, HEALTH_INTERVAL_MS);
                });
            }, "health-check").start();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String engineId = intent == null ? null : intent.getStringExtra("engine");
        if (intent == null || (intent.getStringExtra("host") == null && engineId == null)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundWithType(buildNotification("Подключение…"));

        final String host = intent.getStringExtra("host");
        final int port = intent.getIntExtra("port", 0);
        final boolean useSocks = intent.getBooleanExtra("socks", true);
        final EngineRunner.Kind engineKind = EngineRunner.Kind.from(engineId);
        final int requestedBatch = intent.getIntExtra("batch", 0);

        // Загрузка ядра может занять минуты: только не в главном потоке.
        new Thread(() -> setUp(host, port, useSocks, engineKind, requestedBatch), "vpn-setup").start();
        return START_NOT_STICKY;
    }

    private void setUp(String host, int port, boolean useSocks, EngineRunner.Kind engineKind, int requestedBatch) {
        try {
            List<NodeStore.Node> nodes = new ArrayList<>();
            if (host != null) {
                nodes.add(new NodeStore.Node(host, port, useSocks));
            }
            int added = 0;
            for (NodeStore.Node node : NodeStore.load(this)) {
                if (added++ >= 40) {
                    break;
                }
                nodes.add(node);
            }
            pool = nodes.isEmpty() ? null : new NodePool(nodes);
            if (pool != null && !pool.snapshot().isEmpty()) {
                pool.select(pool.snapshot().get(0));
            }

            if (engineKind != null) {
                File subscription = new File(getFilesDir(), "subscription.json");
                if (!subscription.exists()) {
                    throw new IOException("сначала загрузите подписку кнопкой «Загрузить»");
                }
                subscriptionText = new String(readAll(subscription), StandardCharsets.UTF_8);
                this.engineKind = engineKind;
                batchIndex = Math.max(0, requestedBatch);
                batchCount = EngineConfig.batchCount(subscriptionText);
                engine = new EngineRunner(this);
                engine.start(engineKind, subscriptionText, batchIndex);
                controllerPort = engine.controllerPort();
                engineKindId = engineKind.id;
                Log.i(TAG, "ядро " + engineKind.id + " поднято на 127.0.0.1:" + engine.port());
            }

            socks = new Socks5Server(this, pool, engine);
            int localPort = socks.start();
            socksPort = localPort;

            Builder builder = new Builder();
            builder.setSession("YouTube Proxy");
            builder.addAddress("198.18.0.1", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("1.1.1.1");
            builder.setMtu(1500);

            appliedApps = 0;
            Set<String> allowed = AppSelection.load(this);
            for (String packageName : allowed) {
                try {
                    builder.addAllowedApplication(packageName);
                    appliedApps++;
                } catch (Exception ignored) {
                    // приложение удалили после выбора — пропускаем
                }
            }

            tun = builder.establish();
            if (tun == null) {
                throw new IOException("VPN не разрешён системой");
            }

            File config = new File(getFilesDir(), "hev.yml");
            writeConfig(config, localPort);

            if (!hev.htproxy.TProxyService.TProxyStartService(config.getAbsolutePath(), tun.getFd())) {
                throw new IOException("hev-socks5-tunnel не запустился");
            }

            running = true;
            instance = this;
            publishStatus();
            healthHandler.postDelayed(healthTask, 5000);
        } catch (Exception error) {
            Log.e(TAG, "ошибка запуска", error);
            status = "Ошибка: " + error.getMessage();
            running = false;
            teardown();
            stopSelf();
        }
    }

    /** Смена пачки без перезапуска VPN: ядро перезапускается, SOCKS5-сервер живёт. */
    void switchBatch() {
        if (engine == null || subscriptionText == null) {
            return;
        }
        if (batchIndex + 1 >= batchCount) {
            return;
        }
        final int next = batchIndex + 1;
        new Thread(() -> {
            try {
                engine.stop();
                engine.start(engineKind, subscriptionText, next);
                batchIndex = next;
                publishStatus();
            } catch (Exception error) {
                status = "Смена пачки: " + error.getMessage();
            }
        }, "batch-switch").start();
    }

    /** Вызывается после нескольких сбоев подряд: переходим на следующую пачку. */
    static void requestNextBatch() {
        ProxyVpnService service = instance;
        if (service != null) {
            service.switchBatch();
        }
    }

    private void publishStatus() {
        String via = engine != null && engine.isRunning()
                ? "ядро " + engine.kind().id
                : (pool != null && pool.current() != null ? pool.current().label() : "нода не выбрана");
        String scope = appliedApps > 0 ? "только " + appliedApps + " прил." : "все приложения";
        String failovers = switchCount > 0 ? " · переключений: " + switchCount : "";
        String batch = engine != null && engine.isRunning() && batchCount > 1
                ? " · пачка " + (batchIndex + 1) + "/" + batchCount : "";
        status = "Подключено · " + via + batch + " · " + scope + failovers;
        updateNotification(status);
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private void writeConfig(File file, int localPort) throws IOException {
        String yaml = "tunnel:\n"
                + "  name: tun0\n"
                + "  mtu: 1500\n"
                + "  ipv4: 198.18.0.1\n"
                + "  ipv6: 'fc00::1'\n"
                + "  icmp: 'off'\n"
                + "socks5:\n"
                + "  port: " + localPort + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'tcp'\n"
                + "misc:\n"
                + "  log-level: warn\n"
                + "  connect-timeout: 10000\n";
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Android 14 требует явный тип foreground service. */
    private void startForegroundWithType(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Прокси", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("YouTube Proxy")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(1, buildNotification(text));
    }

    private void teardown() {
        running = false;
        instance = null;
        healthHandler.removeCallbacks(healthTask);
        try {
            hev.htproxy.TProxyService.TProxyStopService();
        } catch (Throwable ignored) {
            // библиотека могла не загрузиться
        }
        if (socks != null) {
            socks.stop();
            socks = null;
        }
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        controllerPort = 0;
        engineKindId = null;
        socksPort = 0;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
            tun = null;
        }
    }

    @Override
    public void onRevoke() {
        status = "VPN отозван системой";
        teardown();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        teardown();
        status = "Выключено";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }
}
