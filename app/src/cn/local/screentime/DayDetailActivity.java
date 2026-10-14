package cn.local.screentime;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Per-app breakdown, either for a whole calendar day or for an arbitrary window.
 *
 * The charge-history screen opens it with a time range (the discharge cycle), which
 * is why day-based and range-based modes both exist: a cycle starts at an arbitrary
 * moment, so it cannot be expressed as "a day". A cycle that starts and ends on the
 * same day would otherwise show the whole day's usage, which is not what
 * "screen time since unplug" means.
 */
public class DayDetailActivity extends Activity {

    public static final String EXTRA_DAY = "day";
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_TO = "to";
    public static final String EXTRA_TITLE = "title";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        long from = getIntent() != null ? getIntent().getLongExtra(EXTRA_FROM, 0) : 0;
        long to = getIntent() != null ? getIntent().getLongExtra(EXTRA_TO, 0) : 0;
        String title = getIntent() != null ? getIntent().getStringExtra(EXTRA_TITLE) : null;
        String day = getIntent() != null ? getIntent().getStringExtra(EXTRA_DAY) : null;

        boolean rangeMode = from > 0 && to > from;
        long now = System.currentTimeMillis();
        if (rangeMode) to = Math.min(to, now);

        UsageRepo repo = new UsageRepo(this);
        Labels labels = new Labels(this);

        List<UsageRepo.AppUse> apps;
        String heading;
        String note = null;
        if (rangeMode) {
            apps = repo.appsInRange(from, to);
            long[] so = repo.screenOnInRange(from, to);
            String endTxt = (to >= now - 1000) ? "现在" : Fmt.hhmm(to);
            heading = (title != null ? title : "这段时间")
                    + "  " + Fmt.hhmm(from) + " → " + endTxt;
            note = "亮屏 " + Fmt.dur(so[0]) + " · 解锁 " + so[1] + " 次";
            if (now - from > 2L * 86400000L) {
                note = note + "\n系统事件仅保留约 2 天，更早的明细可能不全";
            }
        } else {
            if (day == null) day = UsageRepo.today();
            apps = repo.appsOf(day);
            heading = Fmt.dayLabel(day) + "  共 " + Fmt.dur(repo.totalOf(day));
            // Day mode is only correct when the caller really meant a calendar day.
            // If a charge cycle opened this screen without a range, say so instead of
            // quietly showing the whole day.
            if (title != null) {
                note = "未收到时间区间，这里显示的是整个自然日";
            }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));
        int pad = dp(16);

        // header with a back button
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, dp(6));
        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(22);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        LinearLayout headCol = new LinearLayout(this);
        headCol.setOrientation(LinearLayout.VERTICAL);
        headCol.setPadding(dp(8), 0, 0, 0);

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextSize(18);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(Color.parseColor("#202124"));
        headCol.addView(h);

        if (note != null) {
            TextView n = new TextView(this);
            n.setText(note);
            n.setTextSize(12);
            n.setTextColor(Color.parseColor("#5F6368"));
            n.setPadding(0, dp(3), 0, 0);
            headCol.addView(n);
        }
        bar.addView(headCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, 0, pad, pad);

        if (apps.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("这段时间没有应用使用记录。");
            t.setTextSize(13);
            t.setTextColor(Color.parseColor("#5F6368"));
            t.setPadding(0, dp(8), 0, dp(8));
            list.addView(t);
        }
        long total = 0;
        for (UsageRepo.AppUse a : apps) total += a.ms;

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
            } else {
                View spacer = new View(this);
                int s = dp(36);
                row.addView(spacer, new LinearLayout.LayoutParams(s, s));
            }

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(10), 0, dp(8), 0);

            TextView nm = new TextView(this);
            nm.setText(labels.name(a.pkg));
            nm.setTextSize(15);
            nm.setTextColor(Color.parseColor("#202124"));
            nm.setMaxLines(1);
            col.addView(nm);

            TextView pm = new TextView(this);
            int pct = total > 0 ? (int) Math.round(100.0 * a.ms / total) : 0;
            pm.setText(pct + "%  ·  " + Fmt.hhmm(a.firstTs) + "–" + Fmt.hhmm(a.lastTs));
            pm.setTextSize(11);
            pm.setTextColor(Color.parseColor("#8A8A8A"));
            col.addView(pm);

            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tv = new TextView(this);
            tv.setText(Fmt.dur(a.ms));
            tv.setTextSize(15);
            tv.setTextColor(Color.parseColor("#202124"));
            row.addView(tv);

            list.addView(row);
        }

        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        // Keep content clear of the status bar / camera cut-out.
        Insets.apply(this, root);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
