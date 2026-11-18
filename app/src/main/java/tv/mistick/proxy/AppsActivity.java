package tv.mistick.proxy;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Выбор приложений, которые пойдут через VPN. */
public final class AppsActivity extends Activity {

    private final List<ResolveInfo> apps = new ArrayList<>();
    private Set<String> selected = new LinkedHashSet<>();
    private ListView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        apps.addAll(AppSelection.launchableApps(this));
        selected.addAll(AppSelection.load(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101420"));
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Через VPN пойдут только отмеченные приложения");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        root.addView(title);

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setBackgroundColor(Color.parseColor("#151A26"));
        list.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, labels()));
        for (int i = 0; i < apps.size(); i++) {
            list.setItemChecked(i, selected.contains(apps.get(i).activityInfo.packageName));
        }
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button done = new Button(this);
        done.setText("Готово");
        done.setOnClickListener(view -> finish());
        root.addView(done);

        setContentView(root);
    }

    private List<String> labels() {
        PackageManager manager = getPackageManager();
        List<String> out = new ArrayList<>();
        for (ResolveInfo info : apps) {
            out.add(info.loadLabel(manager) + "\n" + info.activityInfo.packageName);
        }
        return out;
    }

    @Override
    protected void onPause() {
        Set<String> chosen = new LinkedHashSet<>();
        if (list != null) {
            for (int i = 0; i < apps.size(); i++) {
                if (list.getCheckedItemPositions().get(i)) {
                    chosen.add(apps.get(i).activityInfo.packageName);
                }
            }
        }
        AppSelection.save(this, chosen);
        super.onPause();
    }
}
