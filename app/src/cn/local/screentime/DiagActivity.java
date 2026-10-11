package cn.local.screentime;

import android.app.Activity;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Read-only probe: compares three ways of measuring "today's screen time" so we
 * can pick the one that matches the reference app.
 *
 *   1. sum of INTERVAL_DAILY buckets over the last 24h
 *   2. sum of buckets whose start falls in the current calendar day
 *   3. exact screen-on pairs (SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE)
 *      from queryEvents, plus summed foreground time from ACTIVITY_RESUMED/PAUSED
 *
 * Nothing is written here.
 */
public class DiagActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        StringBuilder sb = new StringBuilder();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();

        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long dayStart = c.getTimeInMillis();
        long dayEnd = dayStart + 86400000L;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        Utils.append(sb, "现在: " + fmt.format(new Date(now)));
        Utils.append(sb, "今日 00:00 = " + fmt.format(new Date(dayStart)));
        Utils.append(sb, "");

        // --- 0) package identification: what can we actually see? ---
        Labels lb = new Labels(this);
        Utils.append(sb, "[0] 包识别能力");
        Utils.append(sb, "     可识别包总数 = " + lb.knownCount());
        try {
            int nInstalled = getPackageManager().getInstalledPackages(0).size();
            Utils.append(sb, "     getInstalledPackages(0) = " + nInstalled);
            int nLauncher = getPackageManager().queryIntentActivities(
                    new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0).size();
            Utils.append(sb, "     有启动入口的 = " + nLauncher);
        } catch (Throwable t) {
            Utils.append(sb, "     枚举失败: " + t);
        }

        // today's apps, and specifically those we cannot name
        UsageRepo rp = new UsageRepo(this);
        java.util.List<UsageRepo.AppUse> todays = rp.liveApps(UsageRepo.today());
        Utils.append(sb, "     今日有记录的应用 = " + todays.size());
        Utils.append(sb, "     --- 无法识别的包（这些就是归入「其他」的）---");
        int bad = 0;
        for (UsageRepo.AppUse a : todays) {
            if (!lb.isInstalled(a.pkg) && lb.icon(a.pkg) == null) {
                bad++;
                Utils.append(sb, "       pkg=" + a.pkg + "  " + Fmt.dur(a.ms));
                Utils.append(sb, "           " + lb.diagnose(a.pkg));
            }
        }
        if (bad == 0) Utils.append(sb, "       （无）");
        Utils.append(sb, "");
        Utils.append(sb, "     --- 能识别的包（前 15 个）---");
        int good = 0;
        for (UsageRepo.AppUse a : todays) {
            if (lb.isInstalled(a.pkg) || lb.icon(a.pkg) != null) {
                good++;
                if (good <= 15) Utils.append(sb, "       pkg=" + a.pkg + "  " + lb.name(a.pkg));
            }
        }
        Utils.append(sb, "     共 " + good + " 个");
        Utils.append(sb, "");

        // --- 1) last 24h buckets ---
        long t1 = 0;
        List<UsageStats> s1 = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000L, now);
        if (s1 != null) for (UsageStats s : s1) t1 += s.getTotalTimeInForeground();
        Utils.append(sb, "[1] 最近24h 桶求和        = " + Fmt.dur(t1) + "  (" + (s1 == null ? 0 : s1.size()) + " 条)");

        // --- 2) buckets that START inside today ---
        long t2 = 0;
        int n2 = 0;
        List<UsageStats> s2 = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, dayEnd);
        long minStart = Long.MAX_VALUE, maxStart = 0;
        if (s2 != null) {
            for (UsageStats s : s2) {
                t2 += s.getTotalTimeInForeground();
                n2++;
                minStart = Math.min(minStart, s.getFirstTimeStamp());
                maxStart = Math.max(maxStart, s.getLastTimeStamp());
            }
        }
        Utils.append(sb, "[2] 今日桶求和            = " + Fmt.dur(t2) + "  (" + n2 + " 条)");
        if (n2 > 0) Utils.append(sb, "     桶范围 " + fmt.format(new Date(minStart)) + " ~ " + fmt.format(new Date(maxStart)));
        Utils.append(sb, "");

        // --- 3) exact events: screen-on + per-app foreground ---
        Utils.append(sb, "[3] 事件法（自然日精确计算）");
        long scrOn = screenOnSince(usm, dayStart, now);
        Utils.append(sb, "     亮屏时长(SCREEN_INTERACTIVE 配对) = " + Fmt.dur(scrOn));
        long fg = foregroundFromEvents(usm, dayStart, now);
        Utils.append(sb, "     前台时长(ACTIVITY_RESUMED/PAUSED) = " + Fmt.dur(fg));
        Utils.append(sb, "");

        // --- event retention: how far back do events actually go? ---
        Utils.append(sb, "[4] 事件保留深度探测");
        UsageEvents ev = usm.queryEvents(now - 10L * 86400000L, now);
        if (ev != null) {
            UsageEvents.Event e = new UsageEvents.Event();
            long first = Long.MAX_VALUE, last = 0;
            int n = 0;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                long ts = e.getTimeStamp();
                first = Math.min(first, ts);
                last = Math.max(last, ts);
                n++;
            }
            Utils.append(sb, "     10天内事件数 = " + n);
            if (n > 0) {
                Utils.append(sb, "     最早 = " + fmt.format(new Date(first)));
                Utils.append(sb, "     最晚 = " + fmt.format(new Date(last)));
            }
        }
        Utils.append(sb, "");

        // --- 5) per-day breakdown from events, to see which days are intact ---
        Utils.append(sb, "[5] 最近 7 个自然日的亮屏时长（事件法）");
        for (int i = 6; i >= 0; i--) {
            long ds = dayStart - i * 86400000L;
            long v = screenOnSince(usm, ds, Math.min(ds + 86400000L, now));
            SimpleDateFormat d = new SimpleDateFormat("MM-dd", Locale.US);
            Utils.append(sb, "     " + d.format(new Date(ds)) + "  " + Fmt.dur(v));
        }
        Utils.append(sb, "");

        // --- 6) bucket boundaries for reference ---
        Utils.append(sb, "[6] 日桶的边界时间（证明它不按 00:00 切）");
        List<UsageStats> s6 = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                now - 8L * 86400000L, now);
        if (s6 != null) {
            java.util.TreeSet<Long> starts = new java.util.TreeSet<>();
            for (UsageStats s : s6) starts.add(s.getFirstTimeStamp());
            for (Long st : starts) {
                Utils.append(sb, "     " + fmt.format(new Date(st)));
            }
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setTextColor(Color.BLACK);
        tv.setPadding(24, 24, 24, 24);
        tv.setTextIsSelectable(true);

        // A big obvious button so the diagnostic page is reachable by tapping the
        // launcher icon only once.
        android.widget.Button go = new android.widget.Button(this);
        go.setText("进入主界面 →");
        go.setTextSize(16);
        go.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                startActivity(new android.content.Intent(DiagActivity.this, MainActivity.class));
            }
        });

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.addView(go, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tv, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);
        Insets.apply(this, root);
    }


    /** Screen-on milliseconds in [from,to), pairing INTERACTIVE / NON_INTERACTIVE. */
    static long screenOnSince(UsageStatsManager usm, long from, long to) {
        UsageEvents ev = usm.queryEvents(from, to);
        if (ev == null) return 0;
        UsageEvents.Event e = new UsageEvents.Event();
        long total = 0;
        long onAt = -1;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int type = e.getEventType();
            long ts = e.getTimeStamp();
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE) {
                if (onAt < 0) onAt = ts;
            } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (onAt >= 0) { total += ts - onAt; onAt = -1; }
            }
        }
        if (onAt >= 0) total += Math.max(0, to - onAt);
        return total;
    }

    /** Foreground milliseconds in [from,to) by pairing ACTIVITY_RESUMED / PAUSED. */
    static long foregroundFromEvents(UsageStatsManager usm, long from, long to) {
        UsageEvents ev = usm.queryEvents(from, to);
        if (ev == null) return 0;
        UsageEvents.Event e = new UsageEvents.Event();
        long total = 0;
        java.util.HashMap<String, Long> resumed = new java.util.HashMap<>();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            String pkg = e.getPackageName();
            long ts = e.getTimeStamp();
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (!resumed.containsKey(pkg)) resumed.put(pkg, ts);
            } else if (e.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED
                    || e.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED) {
                Long st = resumed.remove(pkg);
                if (st != null) total += Math.max(0, ts - st);
            }
        }
        for (Long st : resumed.values()) total += Math.max(0, to - st);
        return total;
    }
}
