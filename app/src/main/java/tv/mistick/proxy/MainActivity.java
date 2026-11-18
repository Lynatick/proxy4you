package tv.mistick.proxy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Экран под пульт: список нод, подключение кнопкой OK, статус и трафик. */
public final class MainActivity extends Activity {

    private static final int VPN_REQUEST = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<NodeStore.Node> nodes = new ArrayList<>();

    private TextView statusView;
    private Button toggleButton;
    private Button appsButton;
    private NodeStore.Node selected;

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

        TextView hint = new TextView(this);
        hint.setText("Выберите ноду (OK). Список отсортирован по задержке.");
        hint.setTextColor(Color.parseColor("#6B7C8F"));
        hint.setPadding(0, dp(16), 0, dp(8));
        root.addView(hint);

        ListView list = new ListView(this);
        list.setBackgroundColor(Color.parseColor("#151A26"));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels());
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                selected = nodes.get(position);
                connect();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = new TextView(this);
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
        for (NodeStore.Node node : nodes) {
            out.add(node.label());
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
        if (selected == null) {
            selected = nodes.isEmpty() ? null : nodes.get(0);
        }
        connect();
    }

    private void connect() {
        if (selected == null) {
            statusView.setText("Нет нод для подключения");
            return;
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
        intent.putExtra("host", selected.host);
        intent.putExtra("port", selected.port);
        intent.putExtra("socks", selected.socks5);
        startService(intent);
        updateStatus();
    }

    private void updateStatus() {
        boolean isRunning = ProxyVpnService.running;
        statusView.setText(ProxyVpnService.status);
        toggleButton.setText(isRunning ? R.string.disconnect : R.string.connect);
        int count = AppSelection.load(this).size();
        appsButton.setText(count > 0 ? "Приложения для VPN: " + count : "Приложения для VPN: все");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        handler.postDelayed(refresher, 1000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
