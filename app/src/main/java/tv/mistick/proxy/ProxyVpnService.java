package tv.mistick.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
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
 * VPN-сервис: поднимает TUN, отдаёт его hev-socks5-tunnel, а тот ходит в наш
 * локальный SOCKS5-сервер. Тот, в свою очередь, соединяется с удалённой нодой
 * через protect(), поэтому трафик не зацикливается в туннеле.
 */
public final class ProxyVpnService extends VpnService {

    private static final String TAG = "MiStickProxy";
    private static final String CHANNEL_ID = "proxy";

    static volatile boolean running = false;
    static volatile String status = "Выключено";

    private ParcelFileDescriptor tun;
    private Socks5Server socks;
    private String currentNode = "";

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
        currentNode = host + ":" + port;

        try {
            List<NodeStore.Node> nodes = new ArrayList<>();
            nodes.add(new NodeStore.Node(host, port, useSocks));
            List<NodeStore.Node> bundled = NodeStore.load(this);
            for (NodeStore.Node node : bundled) {
                if (bundled.size() < 40) {
                    nodes.add(node);
                }
            }

            socks = new Socks5Server(this, nodes);
            int localPort = socks.start();

            Builder builder = new Builder();
            builder.setSession("YouTube Proxy");
            builder.addAddress("198.18.0.1", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("1.1.1.1");
            builder.setMtu(1500);

            // Только выбранные приложения: остальной трафик телевизора идёт мимо VPN.
            Set<String> allowed = AppSelection.load(this);
            int applied = 0;
            for (String packageName : allowed) {
                try {
                    builder.addAllowedApplication(packageName);
                    applied++;
                } catch (Exception ignored) {
                    // приложение удалили после выбора — просто пропускаем
                }
            }

            tun = builder.establish();
            if (tun == null) {
                throw new IOException("VPN не разрешён системой");
            }

            File config = new File(getFilesDir(), "hev.yml");
            writeConfig(config, localPort);

            boolean started = hev.htproxy.TProxyService.TProxyStartService(config.getAbsolutePath(), tun.getFd());
            if (!started) {
                throw new IOException("hev-socks5-tunnel не запустился");
            }

            running = true;
            String scope = applied > 0 ? "только " + applied + " прил." : "все приложения";
            status = "Подключено · " + currentNode + " · " + scope;
            updateNotification(status);
        } catch (Exception error) {
            Log.e(TAG, "ошибка запуска", error);
            status = "Ошибка: " + error.getMessage();
            running = false;
            teardown();
            stopSelf();
        }

        return START_NOT_STICKY;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                hev.htproxy.TProxyService.TProxyStopService();
            } catch (Throwable ignored) {
                // библиотека могла не загрузиться
            }
        }
        if (socks != null) {
            socks.stop();
            socks = null;
        }
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
