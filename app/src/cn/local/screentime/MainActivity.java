package cn.local.screentime;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Main screen: today's total, a month heatmap calendar, a bar chart of the last
 * two weeks, and the per-app breakdown for the selected day.
 */
public class MainActivity extends Activity {

    private UsageRepo repo;
    private Labels labels;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvDateLabel;
    private TextView tvToday;
    private TextView tvSub;
    private TextView tvMonth;
    private MonthCalendarView cal;
    private BarChartView chart;
    private LinearLayout appList;
    private LinearLayout permBox;

    private String month;      // yyyy-MM currently shown
    private String selected;   // yyyy-MM-dd

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        repo = new UsageRepo(this);
        labels = new Labels(this);

        Calendar c = Calendar.getInstance();
        month = String.format(Locale.US, "%04d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
        selected = UsageRepo.today();

        setContentView(buildUi());
        applyInsetsToContent();
        // Keep the daily archive alarm alive across app launches.
        ArchiveReceiver.schedule(this);
        ui.post(refreshTask);
    }

    /** Push content below the status bar / camera cut-out. */
    private void applyInsetsToContent() {
        View content = findViewById(android.R.id.content);
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            Insets.apply(this, ((ViewGroup) content).getChildAt(0));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refreshTask);
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { refresh(); }
    };

    // ------------------------------------------------------------------- UI

    private View buildUi() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.parseColor("#FAFAFA"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        // permission banner (hidden once granted)
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setBackgroundColor(Color.parseColor("#FFF3CD"));
        permBox.setPadding(pad, pad, pad, pad);
        TextView pt = new TextView(this);
        pt.setText("需要「使用情况访问」权限才能读取亮屏时间");
        pt.setTextColor(Color.parseColor("#7A5B00"));
        pt.setTextSize(14);
        Button pb = new Button(this);
        pb.setText("去授权");
        pb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "请手动到 设置 → 应用 → 特殊权限 → 使用情况访问",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        permBox.addView(pt);
        permBox.addView(pb);
        root.addView(permBox);

        // selected-day summary
        tvDateLabel = new TextView(this);
        tvDateLabel.setTextSize(13);
        tvDateLabel.setTextColor(Color.parseColor("#5F6368"));
        tvDateLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        tvDateLabel.setPadding(0, dp(6), 0, dp(2));
        root.addView(tvDateLabel);

        // duration with prev/next arrows so adjacent days can be browsed quickly
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        Button prevDay = new Button(this);
        prevDay.setText("‹");
        prevDay.setTextSize(24);
        prevDay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shiftDay(-1); }
        });

        tvToday = new TextView(this);
        tvToday.setTextSize(40);
        tvToday.setTextColor(Color.parseColor("#202124"));
        tvToday.setGravity(Gravity.CENTER);

        Button nextDay = new Button(this);
        nextDay.setText("›");
        nextDay.setTextSize(24);
        nextDay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shiftDay(1); }
        });

        head.addView(prevDay);
        head.addView(tvToday, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(nextDay);
        root.addView(head);

        tvSub = new TextView(this);
        tvSub.setTextSize(13);
        tvSub.setTextColor(Color.parseColor("#5F6368"));
        tvSub.setGravity(Gravity.CENTER_HORIZONTAL);
        tvSub.setPadding(0, 0, 0, dp(12));
        root.addView(tvSub);

        // month navigation
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = new Button(this);
        prev.setText("‹");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shiftMonth(-1); }
        });
        tvMonth = new TextView(this);
        tvMonth.setTextSize(17);
        tvMonth.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        Button next = new Button(this);
        next.setText("›");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shiftMonth(1); }
        });
        nav.addView(prev);
        nav.addView(tvMonth, lp);
        nav.addView(next);
        root.addView(nav);

        cal = new MonthCalendarView(this);
        cal.setOnDayClick(new MonthCalendarView.OnDayClick() {
            @Override public void onDay(String day) {
                selected = day;
                chart.setSelected(day);
                renderHeader(day);
                renderApps(day);
            }
        });
        root.addView(cal);

        // bar chart
        TextView t1 = new TextView(this);
        t1.setText("近 14 天");
        t1.setTextSize(15);
        t1.setTextColor(Color.parseColor("#202124"));
        t1.setPadding(0, dp(14), 0, dp(4));
        root.addView(t1);

        chart = new BarChartView(this);
        chart.setOnBarClick(new BarChartView.OnBarClick() {
            @Override public void onDay(String day) {
                selected = day;
                cal.setSelected(day);
                renderHeader(day);
                renderApps(day);
            }
        });
        root.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));

        // per-app list
        TextView t2 = new TextView(this);
        t2.setText("各应用用时");
        t2.setTextSize(15);
        t2.setTextColor(Color.parseColor("#202124"));
        t2.setPadding(0, dp(16), 0, dp(4));
        root.addView(t2);

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appList);

        return sc;
    }

    /** Move the selected day forward/backward and keep every view in sync. */
    private void shiftDay(int delta) {
        String next = UsageRepo.shiftDay(selected, delta);
        // never browse into the future
        if (next.compareTo(UsageRepo.today()) > 0) return;
        selected = next;

        // follow the month when the arrow crosses a month boundary
        String m = selected.length() >= 7 ? selected.substring(0, 7) : month;
        if (!m.equals(month)) {
            month = m;
            renderCalendar();
        }
        cal.setSelected(selected);
        chart.setSelected(selected);
        renderHeader(selected);
        renderApps(selected);
    }

    private void shiftMonth(int d) {        try {
            String[] s = month.split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(s[0]), Integer.parseInt(s[1]) - 1, 1);
            c.add(Calendar.MONTH, d);
            month = String.format(Locale.US, "%04d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
        } catch (Exception ignore) {}
        renderCalendar();
    }

    // ---------------------------------------------------------------- render

    private void refresh() {
        final boolean ok = repo.hasPermission();
        permBox.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) {
            tvToday.setText("--");
            tvSub.setText("尚未授权");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try { repo.refresh(); } catch (Throwable t) { t.printStackTrace(); }
                ui.post(new Runnable() {
                    @Override public void run() {
                        renderHeader(selected);
                        renderCalendar();
                        renderChart();
                        renderApps(selected);
                    }
                });
            }
        }).start();
    }

    /** Header shows the *selected* day, not always today. */
    private void renderHeader(String day) {
        long screenOn, unlocked;
        int unlocks;
        if (day.equals(UsageRepo.today())) {
            // scan live so the number is fresh even before a refresh commits
            UsageRepo.DayScan sc = repo.liveToday();
            screenOn = sc.screenOnMs > 0 ? sc.screenOnMs : repo.totalOf(day);
            unlocked = sc.unlockedMs > 0 ? sc.unlockedMs : repo.unlockedOf(day);
            unlocks = sc.unlockCount > 0 ? sc.unlockCount : repo.unlockCountOf(day);
        } else {
            screenOn = repo.totalOf(day);
            unlocked = repo.unlockedOf(day);
            unlocks = repo.unlockCountOf(day);
        }

        String label = day.equals(UsageRepo.today())
                ? "今日亮屏" : Fmt.dayLabel(day) + " 亮屏";
        tvDateLabel.setText(label);
        tvToday.setText(screenOn > 0 ? Fmt.dur(screenOn) : "--");

        // second line: the other reading of screen time, plus comparison
        String cmp;
        if (day.equals(UsageRepo.today())) {
            long prev = repo.totalOf(UsageRepo.shiftDay(day, -1));
            if (prev > 0) {
                long d = screenOn - prev;
                cmp = (d >= 0 ? "比昨天多 " : "比昨天少 ") + Fmt.dur(Math.abs(d));
            } else {
                cmp = "昨天无数据";
            }
        } else {
            cmp = day;
        }
        tvSub.setText("解锁后 " + Fmt.dur(unlocked) + " · 解锁 " + unlocks + " 次 · " + cmp);
    }

    private void renderCalendar() {
        tvMonth.setText(month.replace("-", " 年 ") + " 月");
        Map<String, Long> all = repo.totals(400);
        cal.setMonth(month);
        cal.setSelected(selected);
        cal.setData(all);
    }

    private void renderChart() {
        List<String> d = new ArrayList<>();
        List<Long> v = new ArrayList<>();
        String day = UsageRepo.today();
        for (int i = 13; i >= 0; i--) {
            String k = UsageRepo.shiftDay(day, -i);
            d.add(k);
            v.add(repo.totalOf(k));
        }
        chart.setSelected(selected);
        chart.setData(d, v);
    }

    private void renderApps(String day) {
        appList.removeAllViews();
        // Today is read live from the system so the list is current even before
        // the background refresh has committed anything.
        List<UsageRepo.AppUse> raw = day.equals(UsageRepo.today())
                ? repo.liveApps(day) : repo.appsOf(day);

        // Usage events also mention packages that are not usable apps (uninstalled
        // long ago, other profiles, system internals). Identification is
        // best-effort, so anything we cannot name is aggregated into one row
        // rather than dropped - that way a detection mistake never loses time.
        List<UsageRepo.AppUse> apps = new ArrayList<>();
        long otherMs = 0;
        int otherCount = 0;
        for (UsageRepo.AppUse a : raw) {
            if (labels.isInstalled(a.pkg) || labels.icon(a.pkg) != null) {
                apps.add(a);
            } else {
                otherMs += a.ms;
                otherCount++;
                android.util.Log.w("ScreenTime", "unidentified pkg=" + a.pkg
                        + " ms=" + a.ms + " | " + labels.diagnose(a.pkg));
            }
        }

        if (apps.isEmpty() && otherMs <= 0) {
            TextView t = new TextView(this);
            t.setText("这一天没有记录（系统只保留最近约 2 天的逐条事件，更早的日期无法还原每日明细）");
            t.setTextColor(Color.parseColor("#5F6368"));
            t.setTextSize(13);
            t.setPadding(0, dp(8), 0, dp(8));
            appList.addView(t);
            return;
        }
        long total = otherMs;
        for (UsageRepo.AppUse a : apps) total += a.ms;

        for (UsageRepo.AppUse a : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            Drawable ic = labels.icon(a.pkg);
            if (ic != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(ic);
                int s = dp(34);
                row.addView(iv, new LinearLayout.LayoutParams(s, s));
            } else {
                // keep the text column aligned even when an icon is missing
                android.view.View spacer = new android.view.View(this);
                int s = dp(34);
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

            appList.addView(row);
        }

        if (otherMs > 0) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            TextView nm = new TextView(this);
            nm.setText("其他（无法识别的 " + otherCount + " 个包）");
            nm.setTextSize(15);
            nm.setTextColor(Color.parseColor("#5F6368"));
            row.addView(nm, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tv = new TextView(this);
            tv.setText(Fmt.dur(otherMs));
            tv.setTextSize(15);
            tv.setTextColor(Color.parseColor("#5F6368"));
            row.addView(tv);

            appList.addView(row);
        }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
