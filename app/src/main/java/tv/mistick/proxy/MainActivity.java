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
        channelView.setPadding(0, dp(2), 0, dp(8));
        root.addView(channelView);

        toggleButton = new Button(this);
        toggleButton.setText(R.string.connect);
        toggleButton.setFocusable(true);
        toggleButton.setOnClickListener(view -> toggle());
        root.addView(toggleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        appsButton = new Button(this);
        appsButton.setFocusable(true);
        appsButton.setOnClickListener(view -> startActivity(new Intent(this, AppsActivity.class)));
        root.addView(appsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subscriptionTitle = new TextView(this);
        subscriptionTitle.setText("Подписка:");
        subscriptionTitle.setTextColor(Color.parseColor("#9FB3C8"));
        subscriptionTitle.setTextSize(14);
        subscriptionTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(subscriptionTitle);

        subscriptionInput = new EditText(this);
        subscriptionInput.setText(Subscription.savedUrl(this));
        subscriptionInput.setHint("URL подписки");
        subscriptionInput.setHintTextColor(Color.parseColor("#6B7C8F"));
        subscriptionInput.setTextColor(Color.WHITE);
        subscriptionInput.setTextSize(14);
        subscriptionInput.setSingleLine(true);
        subscriptionInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(subscriptionInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout subscriptionRow = new LinearLayout(this);
        subscriptionRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(subscriptionRow);

        Button pasteButton = new Button(this);
        pasteButton.setText("Вставить");
        pasteButton.setFocusable(true);
        pasteButton.setOnClickListener(view -> pasteFromClipboard());
        subscriptionRow.addView(pasteButton);

        Button loadButton = new Button(this);
        loadButton.setText("Загрузить");
        loadButton.setFocusable(true);
        loadButton.setOnClickListener(view -> loadSubscription());
        subscriptionRow.addView(loadButton);

        LinearLayout engineRow = new LinearLayout(this);
        engineRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(engineRow);

        singBoxEngine = new CheckBox(this);
        singBoxEngine.setText("Ядро sing-box");
        singBoxEngine.setTextColor(Color.WHITE);
        singBoxEngine.setTextSize(14);
        singBoxEngine.setFocusable(true);
        engineRow.addView(singBoxEngine);

        xrayEngine = new CheckBox(this);
        xrayEngine.setText("Ядро Xray");
        xrayEngine.setTextColor(Color.WHITE);
        xrayEngine.setTextSize(14);
        xrayEngine.setFocusable(true);
        engineRow.addView(xrayEngine);

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

        Button refreshNodesButton = new Button(this);
        refreshNodesButton.setText("Обновить ноды");
        refreshNodesButton.setFocusable(true);
        refreshNodesButton.setOnClickListener(view -> refreshPublicNodes());
        root.addView(refreshNodesButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        root.addView(nextBatchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button accessButton = new Button(this);
        accessButton.setText("Проверить доступ");
        accessButton.setFocusable(true);
        accessButton.setOnClickListener(view -> checkAccess());
        root.addView(accessButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        accessView = new TextView(this);
        accessView.setTextColor(Color.parseColor("#9FB3C8"));
        accessView.setTextSize(13);
        accessView.setPadding(0, dp(6), 0, dp(10));
        root.addView(accessView);

        nodeInfoView = new TextView(this);
        nodeInfoView.setTextColor(Color.parseColor("#9FB3C8"));
        nodeInfoView.setTextSize(12);
        nodeInfoView.setPadding(0, dp(4), 0, dp(10));
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

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            statusView.setText("Буфер обмена пуст");
            return;
        }
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.toString().trim().isEmpty()) {
            statusView.setText("Буфер обмена пуст");
            return;
        }
        subscriptionInput.setText(text.toString().trim());
        statusView.setText("Подписка вставлена — нажмите «Загрузить»");
    }

    private void loadSubscription() {
        final String url = subscriptionInput.getText().toString().trim();
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
                final boolean json = raw.trim().startsWith("{") || raw.trim().startsWith("[");
                handler.post(() -> {
                    refreshNodes();
                    if (socksCount > 0) {
                        statusView.setText("Подписка загружена: " + socksCount + " socks/http нод");
                    } else if (json) {
                        statusView.setText("JSON-подписка сохранена — выберите ядро и подключитесь");
                    } else {
                        statusView.setText("В подписке нет socks/http нод и она не JSON");
                    }
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
        statusView.setText(isRunning ? ProxyVpnService.status : "Выключено");
        String engine = selectedEngine();
        if (isRunning) {
            channelView.setText("Через: " + ProxyVpnService.currentNode);
        } else {
            channelView.setText(engine == null ? "Канал: ноды пула" : "Канал: ядро " + engine);
        }
        toggleButton.setText(isRunning ? R.string.disconnect : R.string.connect);
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
