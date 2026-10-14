package cn.local.screentime;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads screen-on activity from the system and folds it into our own unbounded
 * history.
 *
 * Why events rather than INTERVAL_DAILY buckets: the system's daily buckets are
 * NOT aligned to calendar days - on this device they roll over around 23:0x, so
 * "today's bucket" does not exist until late evening and most of today's usage
 * sits in the previous bucket. Summing buckets therefore reports 0 for today and
 * disagrees with apps that compute per calendar day. queryEvents() lets us use
 * exact local-midnight boundaries instead.
 *
 * Screen-on is the time the display is interactive, which is the metric a
 * "screen time" app should show; per-app time is foreground time from
 * ACTIVITY_RESUMED/PAUSED pairs.
 */
public class UsageRepo {

    /** How far back we bother scanning events. */
    private static final int SCAN_DAYS = 10;

    private final Context ctx;
    private final Db db;

    public UsageRepo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db = new Db(this.ctx);
    }

    private UsageStatsManager usm() {
        return (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    public boolean hasPermission() {
        UsageStatsManager m = usm();
        if (m == null) return false;
        UsageEvents ev = m.queryEvents(System.currentTimeMillis() - 3600_000L,
                System.currentTimeMillis());
        return ev != null && ev.hasNextEvent();
    }

    // ------------------------------------------------------------- date helpers

    public static String dayKey(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(ts));
    }

    public static long dayStartMs(String day) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String shiftDay(String day, int delta) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStartMs(day));
        c.add(Calendar.DAY_OF_YEAR, delta);
        return dayKey(c.getTimeInMillis());
    }

    public static String today() {
        return dayKey(System.currentTimeMillis());
    }

    /** Local midnight of a day, computed via Calendar (DST-safe). */
    private static long midnight(String day) {
        Calendar c = Calendar.getInstance();
        try {
            String[] p = day.split("-");
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]),
                    0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long nextMidnight(String day) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(midnight(day));
        c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------- event reading

    /** Result of scanning one calendar day's events. */
    public static class DayScan {
        public long screenOnMs;      // display interactive (lock screen counts)
        public long unlockedMs;      // keyguard dismissed (lock screen excluded)
        public int unlockCount;
        public long dayStart;
        public long dayEnd;
        public boolean hasEvents;
        public final Map<String, long[]> apps = new LinkedHashMap<>();  // pkg -> {fgMs, first, last}
    }

    /**
     * Scan [from,to) and compute both screen metrics plus per-app foreground time.
     *
     * Per-app uses reference counting because several activities of the same
     * package can be resumed/paused in interleaved order.
     *
     * The two screen metrics:
     *   screenOnMs - SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE pairs. The panel
     *                is lit, so time spent on the lock screen is included.
     *   unlockedMs - KEYGUARD_HIDDEN / KEYGUARD_SHOWN pairs. Starts when the user
     *                actually gets past the lock screen.
     * Keeping both means the UI can show whichever matches the reference app.
     */
    public DayScan scanDay(long from, long to) {
        DayScan out = new DayScan();
        out.dayStart = from;
        out.dayEnd = to;
        UsageStatsManager m = usm();
        if (m == null) return out;

        UsageEvents ev = m.queryEvents(from, to);
        if (ev == null) return out;

        UsageEvents.Event e = new UsageEvents.Event();
        long onAt = -1;          // screen interactive start
        long unlockedAt = -1;    // keyguard hidden start
        Map<String, Integer> refs = new HashMap<>();
        Map<String, Long> opStart = new HashMap<>();

        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int type = e.getEventType();
            long ts = e.getTimeStamp();
            if (ts <= 0) continue;
            out.hasEvents = true;

            if (type == UsageEvents.Event.SCREEN_INTERACTIVE) {
                if (onAt < 0) onAt = ts;
            } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (onAt >= 0) {
                    out.screenOnMs += Math.max(0, ts - onAt);
                    onAt = -1;
                }
            } else if (type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                if (unlockedAt < 0) {
                    unlockedAt = ts;
                    out.unlockCount++;
                }
            } else if (type == UsageEvents.Event.KEYGUARD_SHOWN) {
                if (unlockedAt >= 0) {
                    out.unlockedMs += Math.max(0, ts - unlockedAt);
                    unlockedAt = -1;
                }
            } else if (type == resumeType()) {
                String pkg = e.getPackageName();
                if (pkg == null) continue;
                Integer r = refs.get(pkg);
                int n = (r == null ? 0 : r) + 1;
                refs.put(pkg, n);
                if (n == 1) opStart.put(pkg, ts);
                long[] cur = out.apps.get(pkg);
                if (cur == null) out.apps.put(pkg, new long[]{0, ts, ts});
                else {
                    if (cur[1] == 0 || ts < cur[1]) cur[1] = ts;
                    if (ts > cur[2]) cur[2] = ts;
                }
            } else if (type == pauseType()) {
                String pkg = e.getPackageName();
                if (pkg == null) continue;
                Integer r = refs.get(pkg);
                if (r == null || r <= 0) continue;
                int n = r - 1;
                if (n == 0) {
                    refs.remove(pkg);
                    Long st = opStart.remove(pkg);
                    if (st != null) {
                        long[] cur = out.apps.get(pkg);
                        if (cur == null) cur = new long[]{0, st, ts};
                        cur[0] += Math.max(0, ts - st);
                        if (cur[2] < ts) cur[2] = ts;
                        out.apps.put(pkg, cur);
                    }
                } else {
                    refs.put(pkg, n);
                }
            }
        }

        if (onAt >= 0) out.screenOnMs += Math.max(0, to - onAt);
        if (unlockedAt >= 0) out.unlockedMs += Math.max(0, to - unlockedAt);

        long end = Math.min(to, now());
        for (Map.Entry<String, Long> en : opStart.entrySet()) {
            long d = Math.max(0, end - en.getValue());
            long[] cur = out.apps.get(en.getKey());
            if (cur == null) cur = new long[]{0, en.getValue(), end};
            cur[0] += d;
            if (cur[2] < end) cur[2] = end;
            out.apps.put(en.getKey(), cur);
        }
        return out;
    }

    /** ACTVITY_RESUMED on API 29+, MOVE_TO_FOREGROUND before that. */
    private static int resumeType() {
        return android.os.Build.VERSION.SDK_INT >= 29
                ? UsageEvents.Event.ACTIVITY_RESUMED
                : UsageEvents.Event.MOVE_TO_FOREGROUND;
    }

    private static int pauseType() {
        return android.os.Build.VERSION.SDK_INT >= 29
                ? UsageEvents.Event.ACTIVITY_PAUSED
                : UsageEvents.Event.MOVE_TO_BACKGROUND;
    }

    // ------------------------------------------------------------------ refresh

    /** Fold every day we can still see into our own database. Never prunes. */
    public int refresh() {
        int written = 0;
        String day = today();
        SQLiteDatabase w = db.getWritableDatabase();
        long nowMs = now();

        for (int i = SCAN_DAYS - 1; i >= 0; i--) {
            String d = shiftDay(day, -i);
            long from = midnight(d);
            long to = Math.min(nextMidnight(d), nowMs);
            if (from <= 0 || to <= from) continue;

            // Past days that are already archived do not change, so skip the
            // rescan. Only today keeps being recomputed as the day progresses.
            boolean isToday = (i == 0);
            if (!isToday && alreadyArchived(d)) continue;

            DayScan sc = scanDay(from, to);
            if (!sc.hasEvents) continue;
            written++;

            w.beginTransaction();
            try {
                long total = 0;
                for (Map.Entry<String, long[]> en : sc.apps.entrySet()) {
                    long[] v = en.getValue();
                    if (v[0] <= 0) continue;
                    total += v[0];
                    ContentValues cv = new ContentValues();
                    cv.put("day", d);
                    cv.put("pkg", en.getKey());
                    cv.put("total_ms", v[0]);
                    cv.put("first_ts", v[1]);
                    cv.put("last_ts", v[2]);
                    w.insertWithOnConflict("app_usage", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                ContentValues cv = new ContentValues();
                cv.put("day", d);
                cv.put("total_ms", sc.screenOnMs);
                cv.put("unlocked_ms", sc.unlockedMs);
                cv.put("unlock_count", sc.unlockCount);
                cv.put("app_count", sc.apps.size());
                cv.put("created_at", nowMs);
                w.insertWithOnConflict("day_total", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                w.setTransactionSuccessful();
                android.util.Log.i("ScreenTime", "day " + d
                        + " screenOn=" + sc.screenOnMs / 60000 + "min"
                        + " unlocked=" + sc.unlockedMs / 60000 + "min"
                        + " unlocks=" + sc.unlockCount
                        + " apps=" + sc.apps.size());
            } finally {
                w.endTransaction();
            }
        }
        android.util.Log.i("ScreenTime", "refresh wrote " + written + " days; today screenOn="
                + totalOf(today()) / 60000 + "min unlocked=" + unlockedOf(today()) / 60000 + "min");
        return written;
    }

    // ------------------------------------------------------------ stored queries

    /** True when this past day is already archived and will not change. */
    private boolean alreadyArchived(String day) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT 1 FROM day_total WHERE day=? AND created_at>0",
                new String[]{day});
        boolean b = c.moveToFirst();
        c.close();
        return b;
    }

    public Map<String, Long> totals(int limitDays) {
        Map<String, Long> out = new LinkedHashMap<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT day,total_ms FROM day_total ORDER BY day DESC LIMIT ?",
                new String[]{String.valueOf(limitDays)});
        while (c.moveToNext()) out.put(c.getString(0), c.getLong(1));
        c.close();
        return out;
    }

    public long totalOf(String day) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT total_ms FROM day_total WHERE day=?", new String[]{day});
        long v = c.moveToFirst() ? c.getLong(0) : 0;
        c.close();
        return v;
    }

    public long unlockedOf(String day) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT unlocked_ms FROM day_total WHERE day=?", new String[]{day});
        long v = c.moveToFirst() ? c.getLong(0) : 0;
        c.close();
        return v;
    }

    public int unlockCountOf(String day) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT unlock_count FROM day_total WHERE day=?", new String[]{day});
        int v = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return v;
    }

    /** Live scan of today, so the header is current before a refresh commits. */
    public DayScan liveToday() {
        String d = today();
        long from = midnight(d);
        long to = Math.min(nextMidnight(d), now());
        return scanDay(from, to);
    }

    public boolean hasDay(String day) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT 1 FROM day_total WHERE day=? AND app_count>0", new String[]{day});
        boolean b = c.moveToFirst();
        c.close();
        return b;
    }

    public List<AppUse> appsOf(String day) {
        List<AppUse> out = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT pkg,total_ms,first_ts,last_ts FROM app_usage WHERE day=? ORDER BY total_ms DESC",
                new String[]{day});
        while (c.moveToNext()) {
            out.add(new AppUse(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3)));
        }
        c.close();
        return out;
    }

    /** Live read for today, so the number is fresh even before a refresh lands. */
    public List<AppUse> liveApps(String day) {
        long from = midnight(day);
        long to = Math.min(nextMidnight(day), now());
        return appsInRange(from, to);
    }

    /**
     * Per-app foreground time over an arbitrary window.
     *
     * Derived from events rather than the daily buckets, because a charge cycle
     * starts at an arbitrary time of day and would not line up with a bucket.
     * Reference counting handles packages whose activities resume/pause in an
     * interleaved order.
     */
    public List<AppUse> appsInRange(long from, long to) {
        List<AppUse> out = new ArrayList<>();
        UsageStatsManager m = usm();
        if (m == null || to <= from) return out;
        UsageEvents ev = m.queryEvents(from, to);
        if (ev == null) return out;

        UsageEvents.Event e = new UsageEvents.Event();
        Map<String, Integer> refs = new HashMap<>();
        Map<String, Long> openAt = new HashMap<>();
        Map<String, long[]> acc = new LinkedHashMap<>();     // pkg -> {ms, first, last}
        long end = Math.min(to, now());

        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int type = e.getEventType();
            long ts = e.getTimeStamp();
            String pkg = e.getPackageName();
            if (pkg == null) continue;
            if (type == resumeType()) {
                Integer r = refs.get(pkg);
                int n = (r == null ? 0 : r) + 1;
                refs.put(pkg, n);
                if (n == 1) openAt.put(pkg, ts);
                long[] cur = acc.get(pkg);
                if (cur == null) acc.put(pkg, new long[]{0, ts, ts});
                else {
                    if (cur[1] == 0 || ts < cur[1]) cur[1] = ts;
                    if (ts > cur[2]) cur[2] = ts;
                }
            } else if (type == pauseType()) {
                Integer r = refs.get(pkg);
                if (r == null || r <= 0) continue;
                int n = r - 1;
                if (n == 0) {
                    refs.remove(pkg);
                    Long st = openAt.remove(pkg);
                    long[] cur = acc.get(pkg);
                    if (cur == null) cur = new long[]{0, st == null ? ts : st, ts};
                    if (st != null) cur[0] += Math.max(0, ts - st);
                    if (cur[2] < ts) cur[2] = ts;
                    acc.put(pkg, cur);
                } else {
                    refs.put(pkg, n);
                }
            }
        }
        for (Map.Entry<String, Long> en : openAt.entrySet()) {
            long[] cur = acc.get(en.getKey());
            if (cur == null) cur = new long[]{0, en.getValue(), end};
            cur[0] += Math.max(0, end - en.getValue());
            if (cur[2] < end) cur[2] = end;
            acc.put(en.getKey(), cur);
        }
        for (Map.Entry<String, long[]> en : acc.entrySet()) {
            long[] v = en.getValue();
            if (v[0] <= 0) continue;
            out.add(new AppUse(en.getKey(), v[0], v[1], v[2]));
        }
        Collections.sort(out, new Comparator<AppUse>() {
            @Override public int compare(AppUse a, AppUse b) { return Long.compare(b.ms, a.ms); }
        });
        return out;
    }

    /**
     * Screen-on total and unlock count over an arbitrary window.
     * Returns {screenOnMs, unlockCount}.
     */
    public long[] screenOnInRange(long from, long to) {
        long screen = 0, unlocks = 0;
        UsageStatsManager m = usm();
        if (m == null || to <= from) return new long[]{0, 0};
        UsageEvents ev = m.queryEvents(from, to);
        if (ev == null) return new long[]{0, 0};
        UsageEvents.Event e = new UsageEvents.Event();
        long onAt = -1;
        boolean sawOn = false;
        boolean sawOff = false;
        long firstOff = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int type = e.getEventType();
            long ts = e.getTimeStamp();
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE) {
                if (onAt < 0) onAt = ts;
                sawOn = true;
            } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (!sawOff) { sawOff = true; firstOff = ts; }
                if (onAt >= 0) { screen += Math.max(0, ts - onAt); onAt = -1; }
            } else if (type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                unlocks++;
            }
        }
        if (onAt >= 0) screen += Math.max(0, to - onAt);
        // Window opened with the screen already lit: no INTERACTIVE event inside
        // it, so count from the window start up to the first screen-off.
        if (!sawOn && sawOff && firstOff > from) {
            screen += Math.max(0, firstOff - from);
        }
        return new long[]{screen, unlocks};
    }

    public static class AppUse {
        public final String pkg;
        public final long ms;
        public final long firstTs;
        public final long lastTs;

        public AppUse(String pkg, long ms, long firstTs, long lastTs) {
            this.pkg = pkg;
            this.ms = ms;
            this.firstTs = firstTs;
            this.lastTs = lastTs;
        }
    }
}
