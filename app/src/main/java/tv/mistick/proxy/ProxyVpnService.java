package tv.mistick.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

    private final Handler healthHandler = new Handler(Looper.getMainLooper());

    private ParcelFileDescriptor tun;
    private Socks5Server socks;
    private NodePool pool;
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
        if (intent == null || intent.getStringExtra("host") == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(1, buildNotification("Подключение…"));

        String host = intent.getStringExtra("host");
        int port = intent.getIntExtra("port", 0);
        boolean useSocks = intent.getBooleanExtra("socks", true);

        try {
            List<NodeStore.Node> nodes = new ArrayList<>();
            nodes.add(new NodeStore.Node(host, port, useSocks));
            int added = 0;
            for (NodeStore.Node node : NodeStore.load(this)) {
                if (added++ >= 40) {
                    break;
                }
                nodes.add(node);
            }
            pool = new NodePool(nodes);
            if (!pool.snapshot().isEmpty()) {
                pool.select(pool.snapshot().get(0));
            }

            socks = new Socks5Server(this, pool);
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
            publishStatus();
            healthHandler.postDelayed(healthTask, 5000);
        } catch (Exception error) {
            Log.e(TAG, "ошибка запуска", error);
            status = "Ошибка: " + error.getMessage();
            running = false;
            teardown();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void publishStatus() {
        NodePool.Entry active = pool == null ? null : pool.current();
        String node = active == null ? "нода не выбрана" : active.label();
        String scope = appliedApps > 0 ? "только " + appliedApps + " прил." : "все приложения";
        String failovers = switchCount > 0 ? " · переключений: " + switchCount : "";
        status = "Подключено · " + node + " · " + scope + failovers;
        updateNotification(status);
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
