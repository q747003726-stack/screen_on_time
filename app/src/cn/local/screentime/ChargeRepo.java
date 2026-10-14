package cn.local.screentime;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Charger plug/unplug tracking and the screen time that follows each unplug.
 *
 * How the unplug moment is known: ACTION_POWER_DISCONNECTED is a normal broadcast
 * that can be registered statically, so the system starts our process just to
 * deliver it. Nothing stays resident, and no alarm has to wake the device.
 *
 * Why the charge counter is not used to back-estimate the unplug time (measured on
 * this device, see the battery probes): CHARGE_COUNTER moves in 1000 uAh steps and
 * updates only every ~10 s, it was observed to drift non-monotonically, and two
 * readings at the same 69% differed by 14000 uAh. At the observed ~100 uA idle
 * drain that is already a ~10 s floor before load variation, which makes it
 * useless for a minute-accurate timeline. So: broadcast only, and a cycle that
 * predates the first recorded unplug is labelled as an estimate.
 */
public class ChargeRepo {

    public static final int SRC_BROADCAST = 1;   // from ACTION_POWER_*_CHANGED
    public static final int SRC_STICKY = 2;      // inferred from the sticky battery state
    public static final int SRC_ESTIMATE = 3;    // no real record; labelled as a guess

    /** How far back we bother scanning usage events for a cycle's screen time. */
    private static final long MAX_SCAN_MS = 3L * 86400000L;

    private final Context ctx;
    private final Db db;

    public ChargeRepo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db = new Db(this.ctx);
    }

    private UsageStatsManager usm() {
        return (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    // ------------------------------------------------------------ plugged state

    /** Current charger state straight from the sticky battery broadcast; -1 unknown. */
    public int currentPlugged() {
        try {
            Intent it = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it == null) return -1;
            return it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public boolean isChargingNow() {
        int p = currentPlugged();
        return p > 0;
    }

    // --------------------------------------------------------------- recording

    /**
     * Persist a charger transition. Returns true when a new row was written.
     * Duplicate timestamps collapse because ts is the primary key.
     */
    public boolean record(int plugged, int source) {
        long now = System.currentTimeMillis();
        SQLiteDatabase w = db.getWritableDatabase();
        // Ignore a transition identical to the last one (broadcasts can repeat).
        Cursor c = w.rawQuery("SELECT plugged FROM charge_event ORDER BY ts DESC LIMIT 1", null);
        int last = c.moveToFirst() ? c.getInt(0) : -1;
        c.close();
        if (last == plugged) return false;

        ContentValues v = new ContentValues();
        v.put("ts", now);
        v.put("plugged", plugged);
        v.put("elapsed", SystemClock.elapsedRealtime());
        v.put("source", source);
        long r = w.insertWithOnConflict("charge_event", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        android.util.Log.i("ScreenTime", "charge event plugged=" + plugged
                + " source=" + source + " ts=" + now + " inserted=" + (r > 0));
        return r > 0;
    }

    /**
     * Called on app start / boot: reconcile our table with the charger state the
     * system reports right now, so a missed broadcast does not leave a hole.
     */
    public void syncWithSticky() {
        int p = currentPlugged();
        if (p < 0) return;
        int want = p > 0 ? 1 : 0;
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT plugged FROM charge_event ORDER BY ts DESC LIMIT 1", null);
        int have = c.moveToFirst() ? c.getInt(0) : -1;
        c.close();
        if (have != want) {
            record(want, SRC_STICKY);
        }
    }

    /** Earliest timestamp we can speak about; 0 when nothing is recorded. */
    public long trackingStart() {
        Cursor c = db.getReadableDatabase().rawQuery("SELECT MIN(ts) FROM charge_event", null);
        long v = c.moveToFirst() ? c.getLong(0) : 0;
        c.close();
        return v;
    }

    /** Timestamp of the most recent unplug, or 0 when none is recorded. */
    public long lastUnplug() {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT ts FROM charge_event WHERE plugged=0 ORDER BY ts DESC LIMIT 1", null);
        long v = c.moveToFirst() ? c.getLong(0) : 0;
        c.close();
        return v;
    }

    // ------------------------------------------------------------------ cycles

    /** One discharge cycle: from an unplug to the next plug (or up to now). */
    public static class Cycle {
        public long startTs;
        public long endTs;          // now for the ongoing cycle
        public long screenOnMs;
        public int unlockCount;
        public boolean ongoing;
        public boolean estimated;   // start time is a guess, not a recorded unplug
        public long screenOnCalibratedTs;   // 0 = must stay a lower bound
    }

    /**
     * Build the cycle list, newest first.
     *
     * Screen time per cycle comes from usage events, which only reach back about
     * two days. When a cycle started earlier than that, the screen-on figure can
     * still be the true value as long as the events we read cover the whole span;
     * otherwise it is a lower bound and screenOnCalibratedTs records where the
     * trustworthy part begins so the UI can label it.
     */
    public List<Cycle> cycles() {
        List<Long> events = new ArrayList<>();       // alternating (ts, plugged)
        List<Integer> plugged = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT ts, plugged FROM charge_event ORDER BY ts ASC", null);
        while (c.moveToNext()) {
            events.add(c.getLong(0));
            plugged.add(c.getInt(1));
        }
        c.close();

        long now = System.currentTimeMillis();
        long earliestEvents = earliestEventTs();
        List<Cycle> out = new ArrayList<>();

        for (int i = 0; i < events.size(); i++) {
            if (plugged.get(i) != 0) continue;               // want an unplug
            long start = events.get(i);
            // the cycle ends at the next transition to plugged, else "now"
            long end = now;
            for (int j = i + 1; j < events.size(); j++) {
                if (plugged.get(j) == 1) { end = events.get(j); break; }
            }
            Cycle cy = new Cycle();
            cy.startTs = start;
            cy.endTs = end;
            cy.ongoing = (end == now);
            cy.estimated = false;
            fillScreenOn(cy, earliestEvents, now);
            out.add(cy);
        }

        // If nothing has ever been unplugged while we were watching, still show the
        // current discharge span so the card is not empty - clearly marked estimated.
        if (out.isEmpty()) {
            int p = currentPlugged();
            if (p == 0) {
                long start = trackingStart();
                if (start <= 0) start = now - SystemClock.elapsedRealtime();
                Cycle cy = new Cycle();
                cy.startTs = start;
                cy.endTs = now;
                cy.ongoing = true;
                cy.estimated = true;
                fillScreenOn(cy, earliestEvents, now);
                out.add(cy);
            }
        }

        // newest first
        java.util.Collections.sort(out, new java.util.Comparator<Cycle>() {
            @Override public int compare(Cycle a, Cycle b) { return Long.compare(b.startTs, a.startTs); }
        });
        return out;
    }

    private void fillScreenOn(Cycle cy, long earliestEvents, long now) {
        long from = cy.startTs;
        if (earliestEvents > 0 && from < earliestEvents) {
            // events do not reach back this far: the total would be a lower bound
            cy.screenOnCalibratedTs = 0;
            from = earliestEvents;
        } else {
            cy.screenOnCalibratedTs = cy.startTs;
        }
        long to = Math.min(cy.endTs, now);
        if (to <= from) {
            cy.screenOnMs = 0;
            cy.unlockCount = 0;
            return;
        }
        // Cap the scan: a very old ongoing cycle would otherwise read days of events.
        if (now - from > MAX_SCAN_MS) from = now - MAX_SCAN_MS;
        long[] res = screenAndUnlocks(from, to);
        cy.screenOnMs = res[0];
        cy.unlockCount = (int) res[1];
    }

    /** Earliest timestamp any usage event exists for; 0 when none. */
    private long earliestEventTs() {
        UsageStatsManager m = usm();
        if (m == null) return 0;
        long now = System.currentTimeMillis();
        UsageEvents ev = m.queryEvents(now - MAX_SCAN_MS, now);
        if (ev == null) return 0;
        UsageEvents.Event e = new UsageEvents.Event();
        long first = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long ts = e.getTimeStamp();
            if (ts > 0) { first = ts; break; }
            }
        return first;
    }

    /** {screenOnMs, unlockCount} over [from,to). */
    private long[] screenAndUnlocks(long from, long to) {
        long screen = 0, unlocks = 0;
        UsageStatsManager m = usm();
        if (m == null) return new long[]{0, 0};
        UsageEvents ev = m.queryEvents(from, to);
        if (ev == null) return new long[]{0, 0};
        UsageEvents.Event e = new UsageEvents.Event();
        long onAt = -1;
        boolean sawOn = false;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int type = e.getEventType();
            long ts = e.getTimeStamp();
            if (type == UsageEvents.Event.SCREEN_INTERACTIVE) {
                if (onAt < 0) onAt = ts;
                sawOn = true;
            } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                if (onAt >= 0) { screen += Math.max(0, ts - onAt); onAt = -1; }
            } else if (type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                unlocks++;
            }
        }
        if (onAt >= 0) screen += Math.max(0, to - onAt);

        // If the window opens with the screen already lit, no SCREEN_INTERACTIVE
        // event falls inside it, so the time from `from` until the first
        // SCREEN_NON_INTERACTIVE would be lost. A first event of NON_INTERACTIVE
        // while we never saw an INTERACTIVE is exactly that case.
        if (!sawOn && screen == 0) {
            long firstTs = firstEventTs(from, to);
            if (firstTs > 0 && firstTs > from) {
                screen = Math.max(0, firstTs - from);
            } else if (firstTs == 0) {
                // no screen events at all in the window: the display stayed in one
                // state the whole time. Treat an interactive display as lit.
                if (isInteractiveNow()) screen = Math.max(0, to - from);
            }
        }
        return new long[]{screen, unlocks};
    }

    /** Timestamp of the first SCREEN_NON_INTERACTIVE in the window, else 0. */
    private long firstEventTs(long from, long to) {
        UsageStatsManager m = usm();
        if (m == null) return 0;
        UsageEvents ev = m.queryEvents(from, to);
        if (ev == null) return 0;
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                return e.getTimeStamp();
            }
            if (e.getEventType() == UsageEvents.Event.SCREEN_INTERACTIVE) {
                return 0;   // a proper pair exists; handled by the main loop
            }
        }
        return 0;
    }

    /** Whether the display is interactive right now, via the sticky battery intent. */
    private boolean isInteractiveNow() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }
}
