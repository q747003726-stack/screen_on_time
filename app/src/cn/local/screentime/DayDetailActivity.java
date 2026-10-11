package cn.local.screentime;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Per-app detail for a single day. */
public class DayDetailActivity extends Activity {

    public static final String EXTRA_DAY = "day";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String day = getIntent() != null ? getIntent().getStringExtra(EXTRA_DAY) : null;
        if (day == null) day = UsageRepo.today();

        UsageRepo repo = new UsageRepo(this);
        Labels labels = new Labels(this);
        List<UsageRepo.AppUse> apps = repo.appsOf(day);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.parseColor("#FAFAFA"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        TextView h = new TextView(this);
        h.setText(Fmt.dayLabel(day) + "  共 " + Fmt.dur(repo.totalOf(day)));
        h.setTextSize(20);
        h.setTextColor(Color.parseColor("#202124"));
        h.setPadding(0, 0, 0, dp(12));
        root.addView(h);

        for (UsageRepo.AppUse a : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            Drawable ic = labels.icon(a.pkg);
            if (ic != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(ic);
                int s = dp(36);
                row.addView(iv, new LinearLayout.LayoutParams(s, s));
            }
            TextView nm = new TextView(this);
            nm.setText(labels.name(a.pkg));
            nm.setTextSize(15);
            nm.setPadding(dp(10), 0, dp(8), 0);
            row.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tv = new TextView(this);
            tv.setText(Fmt.dur(a.ms));
            tv.setTextSize(15);
            row.addView(tv);

            root.addView(row);
        }
        setContentView(sc);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
