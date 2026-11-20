package tv.mistick.proxy;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Экран под пульт: список нод, подключение кнопкой OK, статус и трафик. */
public final class MainActivity extends Activity {

    private static final int VPN_REQUEST = 1001;
    private static final int NOTIFICATION_REQUEST = 1002;
    private static final String PREFS = "engine";
    private static final String KEY_ENGINE = "selected";
    private static final String PREFS_UPDATES = "updates";
    private static final String KEY_LAST_CHECK = "lastCheck";
    private static final long UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<NodeStore.Node> nodes = new ArrayList<>();

    private TextView statusView;
    private TextView channelView;
    private Button toggleButton;
    private Button appsButton;
    private TextView accessView;
    private TextView footer;
    private TextView nodeInfoView;
    private EditText subscriptionInput;
    private ArrayAdapter<String> adapter;
    private NodeStore.Node selected;
    private CheckBox singBoxEngine;
    private CheckBox xrayEngine;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nodes.addAll(NodeStore.load(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101420"));
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("YouTube Proxy");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#9FB3C8"));
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(6), 0, dp(16));
        root.addView(statusView);

        channelView = new TextView(this);
        channelView.setTextColor(Color.parseColor("#9FB3C8"));
        channelView.setTextSize(14);
        channelView.setPadding(0, dp(2), 0, dp(6));
        root.addView(channelView);

        // Строка 1: выбор ядра и кнопка подключения рядом.
        LinearLayout engineRow = new LinearLayout(this);
        engineRow.setOrientation(LinearLayout.HORIZONTAL);
        engineRow.setGravity(Gravity.CENTER_VERTICAL);
        engineRow.setPadding(0, dp(4), 0, dp(4));
        root.addView(engineRow);

        singBoxEngine = new CheckBox(this);
        singBoxEngine.setText("sing-box");
        singBoxEngine.setTextColor(Color.WHITE);
        singBoxEngine.setTextSize(14);
        singBoxEngine.setFocusable(true);
        engineRow.addView(singBoxEngine);

        xrayEngine = new CheckBox(this);
        xrayEngine.setText("Xray");
        xrayEngine.setTextColor(Color.WHITE);
        xrayEngine.setTextSize(14);
        xrayEngine.setFocusable(true);
        engineRow.addView(xrayEngine);

        toggleButton = new Button(this);
        toggleButton.setText(R.string.connect);
        toggleButton.setFocusable(true);
        toggleButton.setOnClickListener(view -> toggle());
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.setMargins(dp(12), 0, dp(12), 0);
        engineRow.addView(toggleButton, toggleParams);

        appsButton = new Button(this);
        appsButton.setFocusable(true);
        appsButton.setOnClickListener(view -> startActivity(new Intent(this, AppsActivity.class)));
        engineRow.addView(appsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button accessButton = new Button(this);
        accessButton.setText("Проверить доступ");
        accessButton.setFocusable(true);
        accessButton.setOnClickListener(view -> checkAccess());
        LinearLayout.LayoutParams accessParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        accessParams.setMargins(dp(8), 0, 0, 0);
        engineRow.addView(accessButton, accessParams);

        String savedEngine = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ENGINE, "");
        if ("sing-box".equals(savedEngine)) {
            singBoxEngine.setChecked(true);
        } else if ("xray".equals(savedEngine)) {
            xrayEngine.setChecked(true);
        }

        singBoxEngine.setOnCheckedChangeListener((view, checked) -> {
            if (checked) xrayEngine.setChecked(false);
            saveSelectedEngine();
            applyEngineChange();
        });
        xrayEngine.setOnCheckedChangeListener((view, checked) -> {
            if (checked) singBoxEngine.setChecked(false);
            saveSelectedEngine();
            applyEngineChange();
        });

        // Строка 2: подписка — подпись, компактное поле и одна кнопка.
        LinearLayout subscriptionRow = new LinearLayout(this);
        subscriptionRow.setOrientation(LinearLayout.HORIZONTAL);
        subscriptionRow.setGravity(Gravity.CENTER_VERTICAL);
        subscriptionRow.setPadding(0, dp(4), 0, dp(4));
        root.addView(subscriptionRow);

        TextView subscriptionTitle = new TextView(this);
        subscriptionTitle.setText("Подписка");
        subscriptionTitle.setTextColor(Color.parseColor("#9FB3C8"));
        subscriptionTitle.setTextSize(12);
        subscriptionTitle.setPadding(0, 0, dp(8), 0);
        subscriptionRow.addView(subscriptionTitle);

        subscriptionInput = new EditText(this);
        subscriptionInput.setText(Subscription.savedUrl(this));
        subscriptionInput.setHint("URL подписки");
        subscriptionInput.setHintTextColor(Color.parseColor("#6B7C8F"));
        subscriptionInput.setTextColor(Color.WHITE);
        subscriptionInput.setTextSize(12);
        subscriptionInput.setSingleLine(true);
        subscriptionInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        subscriptionInput.setPadding(dp(6), dp(2), dp(6), dp(2));
        subscriptionRow.addView(subscriptionInput, new LinearLayout.LayoutParams(
                dp(420), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button pasteAndLoadButton = new Button(this);
        pasteAndLoadButton.setText("Вставить и загрузить");
        pasteAndLoadButton.setTextSize(12);
        pasteAndLoadButton.setFocusable(true);
        pasteAndLoadButton.setOnClickListener(view -> pasteAndLoad());
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pasteParams.setMargins(dp(8), 0, 0, 0);
        subscriptionRow.addView(pasteAndLoadButton, pasteParams);

        // Строка 3: ноды, пачка и обновление — рядом.
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        actionsRow.setPadding(0, dp(4), 0, dp(4));
        root.addView(actionsRow);

        Button refreshNodesButton = new Button(this);
        refreshNodesButton.setText("Обновить ноды");
        refreshNodesButton.setFocusable(true);
        refreshNodesButton.setOnClickListener(view -> refreshPublicNodes());
        actionsRow.addView(refreshNodesButton);

        Button nextBatchButton = new Button(this);
        nextBatchButton.setText("Следующая пачка");
        nextBatchButton.setFocusable(true);
        nextBatchButton.setOnClickListener(view -> {
            if (ProxyVpnService.running) {
                ProxyVpnService.requestNextBatch();
            } else {
                statusView.setText("Сначала подключитесь через ядро");
            }
        });
        actionsRow.addView(nextBatchButton);

        Button updateButton = new Button(this);
        updateButton.setText("Обновить приложение");
        updateButton.setFocusable(true);
        updateButton.setOnClickListener(view -> checkUpdates(false));
        actionsRow.addView(updateButton);

        accessView = new TextView(this);
        accessView.setTextColor(Color.parseColor("#9FB3C8"));
        accessView.setTextSize(13);
        accessView.setPadding(0, dp(6), 0, dp(6));
        root.addView(accessView);

        nodeInfoView = new TextView(this);
        nodeInfoView.setTextColor(Color.parseColor("#9FB3C8"));
        nodeInfoView.setTextSize(12);
        nodeInfoView.setPadding(0, dp(2), 0, dp(8));
        root.addView(nodeInfoView);

        TextView hint = new TextView(this);
        hint.setText("Выберите ноду (OK). Список отсортирован по задержке.");
        hint.setTextColor(Color.parseColor("#6B7C8F"));
        hint.setPadding(0, dp(16), 0, dp(8));
        root.addView(hint);

        ListView list = new ListView(this);
        list.setBackgroundColor(Color.parseColor("#151A26"));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels());
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < nodes.size()) {
                    selected = nodes.get(position);
                    connect();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footer = new TextView(this);
        footer.setText("Нод в списке: " + nodes.size());
        footer.setTextColor(Color.parseColor("#6B7C8F"));
        footer.setGravity(Gravity.END);
        footer.setPadding(0, dp(8), 0, 0);
        root.addView(footer);

        setContentView(root);
        toggleButton.requestFocus();
        maybeCheckUpdatesSilently();
    }

    private List<String> labels() {
        List<String> out = new ArrayList<>();
        String active = ProxyVpnService.currentNode;
        for (NodeStore.Node node : nodes) {
            String label = node.label();
            out.add(label.equals(active) ? "▶ " + label : label);
        }
        if (out.isEmpty()) {
            out.add("Нет нод — пересоберите экспорт с Mac");
        }
        return out;
    }

    private void toggle() {
        if (ProxyVpnService.running) {
            stopService(new Intent(this, ProxyVpnService.class));
            return;
        }
        connect();
    }

    private String selectedEngine() {
        if (singBoxEngine != null && singBoxEngine.isChecked()) return "sing-box";
        if (xrayEngine != null && xrayEngine.isChecked()) return "xray";
        return null;
    }

    private void saveSelectedEngine() {
        String engine = selectedEngine();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ENGINE, engine == null ? "" : engine)
                .apply();
    }

    /** Смена ядра на подключённом VPN: перезапускаем канал с новым ядром. */
    private void applyEngineChange() {
        if (!ProxyVpnService.running) {
            updateStatus();
            return;
        }
        statusView.setText("Переключение канала…");
        stopService(new Intent(this, ProxyVpnService.class));
        handler.postDelayed(this::connect, 700);
    }

    private void connect() {
        String engine = selectedEngine();
        if (engine != null && !new java.io.File(getFilesDir(), "subscription.json").exists()) {
            statusView.setText("Сначала загрузите подписку кнопкой «Загрузить», затем подключитесь");
            return;
        }
        if (engine == null && nodes.isEmpty()) {
            statusView.setText("Нет нод: загрузите подписку или выберите ядро");
            return;
        }
        if (selected == null && !nodes.isEmpty()) {
            selected = nodes.get(0);
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            startActivityForResult(permission, VPN_REQUEST);
        } else {
            startProxy();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startProxy();
        } else if (requestCode == VPN_REQUEST) {
            statusView.setText("Разрешение VPN не выдано");
        }
    }

    private void startProxy() {
        Intent intent = new Intent(this, ProxyVpnService.class);
        if (selected != null) {
            intent.putExtra("host", selected.host);
            intent.putExtra("port", selected.port);
            intent.putExtra("socks", selected.socks5);
        }
        String engine = selectedEngine();
        if (engine != null) {
            intent.putExtra("engine", engine);
        }
        startService(intent);
        updateStatus();
    }

    /** Одна кнопка: если поле пустое — берём из буфера, затем загружаем. */
    private void pasteAndLoad() {
        String url = subscriptionInput.getText().toString().trim();
        if (url.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence text = null;
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0) {
                text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
            }
            if (text == null || text.toString().trim().isEmpty()) {
                statusView.setText("Буфер обмена пуст — введите URL подписки");
                return;
            }
            url = text.toString().trim();
            subscriptionInput.setText(url);
        }
        loadSubscription(url);
    }

    private void loadSubscription(final String url) {
        if (url.isEmpty()) {
            statusView.setText("Вставьте ссылку на подписку");
            return;
        }
        // Сохраняем URL сразу: он останется даже если сеть недоступна.
        Subscription.saveUrl(this, url);
        statusView.setText("Загрузка подписки…");
        new Thread(() -> {
            try {
                String raw = Subscription.downloadRaw(this, url);
                List<NodeStore.Node> socks = Subscription.parse(raw);
                if (!socks.isEmpty()) {
                    Subscription.saveNodes(this, socks);
                }
                final int socksCount = socks.size();
                handler.post(() -> {
                    refreshNodes();
                    statusView.setText(socksCount > 0
                            ? "Подписка загружена: " + socksCount + " socks/http нод"
                            : "Подписка загружена: ядра могут подключаться");
                });
            } catch (final Exception error) {
                handler.post(() -> statusView.setText("Ошибка подписки: " + error.getMessage()));
            }
        }, "subscription").start();
    }

    private void refreshNodes() {
        nodes.clear();
        nodes.addAll(NodeStore.load(this));
        if (selected == null && !nodes.isEmpty()) {
            selected = nodes.get(0);
        }
        if (adapter != null) {
            adapter.clear();
            adapter.addAll(labels());
            adapter.notifyDataSetChanged();
        }
        if (footer != null) {
            footer.setText("Нод в списке: " + nodes.size());
        }
    }

    /** Тихая проверка не чаще раза в час: GitHub API без токена лимитирован. */
    private void maybeCheckUpdatesSilently() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_UPDATES, MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);
        long now = System.currentTimeMillis();
        if (now - last < UPDATE_CHECK_INTERVAL_MS) {
            return;
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();
        checkUpdates(true);
    }

    /** Проверка обновления и передача APK системному установщику. */
    private void checkUpdates(final boolean silent) {
        getSharedPreferences(PREFS_UPDATES, MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        if (!silent) {
            statusView.setText("Проверка обновлений…");
        }
        new Thread(() -> {
            try {
                final String current = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
                UpdateChecker.Release release = UpdateChecker.latest();
                if (!UpdateChecker.isNewer(release.version, current)) {
                    if (!silent) {
                        handler.post(() -> statusView.setText("Обновлений нет (версия " + current + ")"));
                    }
                    return;
                }
                if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                    handler.post(() -> {
                        statusView.setText("Разрешите установку приложений из этого приложения");
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                            // на некоторых ТВ настройки недоступны — останется сообщение
                        }
                    });
                    return;
                }
                handler.post(() -> statusView.setText("Скачивание " + release.version + "…"));
                final java.io.File apk = Updater.download(this, release.apkUrl);
                handler.post(() -> {
                    statusView.setText("Установка " + release.version + " — подтвердите на ТВ");
                    startActivity(Updater.installIntent(apk));
                });
            } catch (final Exception error) {
                if (!silent) {
                    handler.post(() -> statusView.setText("Ошибка обновления: " + error.getMessage()));
                }
            }
        }, "update-check").start();
    }

    /** Публичные списки нод: те же шесть источников, что в macOS-версии. */
    private void refreshPublicNodes() {
        statusView.setText("Загрузка публичных списков…");
        new Thread(() -> {
            final List<NodeStore.Node> fetched = NodeSources.fetchAll(this);
            handler.post(() -> {
                refreshNodes();
                statusView.setText("Публичные списки: " + fetched.size() + " нод");
            });
        }, "node-sources").start();
    }

    /** Текущая нода ядра и задержки нод пачки (clash_api sing-box). */
    private void updateEngineNodes() {
        if (nodeInfoView == null) {
            return;
        }
        if (!ProxyVpnService.running) {
            nodeInfoView.setText("");
            return;
        }
        String kind = ProxyVpnService.engineKindId;
        if (kind == null) {
            nodeInfoView.setText("Ядро не выбрано — работают ноды пула");
            return;
        }
        if (!"sing-box".equals(kind)) {
            nodeInfoView.setText("Xray: текущую ноду ядро не отдаёт");
            return;
        }
        final int controller = ProxyVpnService.controllerPort;
        if (controller == 0) {
            nodeInfoView.setText("");
            return;
        }
        new Thread(() -> {
            String current = ClashApi.current(controller);
            List<ClashApi.NodeInfo> nodes = ClashApi.nodes(controller, 6);
            StringBuilder text = new StringBuilder();
            if (current != null) {
                String[] parts = current.split("\\|");
                text.append("Сейчас: ").append(parts[0]);
                if (parts.length > 1 && !"-1".equals(parts[1])) {
                    text.append(" · ").append(parts[1]).append(" мс");
                }
                text.append('\n');
            }
            for (ClashApi.NodeInfo info : nodes) {
                text.append(info.name).append(" · ").append(info.delay).append(" мс\n");
            }
            final String result = text.toString().trim();
            handler.post(() -> nodeInfoView.setText(result));
        }, "clash-api").start();
    }

    private void checkAccess() {
        final int socksPort = ProxyVpnService.socksPort;
        accessView.setText(socksPort > 0 ? "Проверка через прокси…" : "Проверка напрямую…");
        new Thread(() -> {
            AccessCheck.Result[] results = AccessCheck.check(socksPort);
            StringBuilder text = new StringBuilder(socksPort > 0 ? "Через прокси:" : "Напрямую:");
            for (AccessCheck.Result result : results) {
                text.append("\n").append(result.name).append(": ").append(result.text());
            }
            handler.post(() -> accessView.setText(text.toString()));
        }, "access-check").start();
    }

    private void updateStatus() {
        boolean isRunning = ProxyVpnService.running;
        String serviceStatus = ProxyVpnService.status;
        String engine = selectedEngine();

        if (isRunning) {
            statusView.setText(serviceStatus);
            channelView.setText("Через: " + ProxyVpnService.currentNode);
            toggleButton.setText(R.string.disconnect);
        } else {
            // Ошибку старта не скрываем: она объясняет, почему кнопка осталась «Подключить».
            boolean failed = serviceStatus != null && serviceStatus.startsWith("Ошибка");
            statusView.setText(failed ? serviceStatus : "Выключено");
            channelView.setText(engine == null ? "Канал: ноды пула" : "Канал: ядро " + engine);
            toggleButton.setText(engine == null ? getString(R.string.connect) : "Подключить через " + engine);
        }
        int count = AppSelection.load(this).size();
        appsButton.setText(count > 0 ? "Приложения для VPN: " + count : "Приложения для VPN: все");
    }

    private final Runnable nodeRefresher = new Runnable() {
        @Override
        public void run() {
            updateEngineNodes();
            handler.postDelayed(this, 5000);
        }
    };

    /** Android 13+ требует явного разрешения на уведомление foreground-сервиса. */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestNotificationPermission();
        updateStatus();
        handler.postDelayed(refresher, 1000);
        maybeCheckUpdatesSilently();
        handler.postDelayed(nodeRefresher, 1000);
        if (!new java.io.File(getFilesDir(), "public_nodes.txt").exists()) {
            refreshPublicNodes();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        handler.removeCallbacks(nodeRefresher);
        super.onPause();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
