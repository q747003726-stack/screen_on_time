package cn.local.screentime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Local history store.
 *
 * The system only keeps a short rolling window of raw events (about two days on
 * this device), so the app copies what it can still see into its own database on
 * every refresh. Nothing is pruned here: the history grows without limit, which
 * is the point of not using the original app.
 *
 * Two screen metrics are kept side by side because the two plausible readings of
 * "screen time" differ by whether the lock screen counts:
 *   screen_on_ms  - the display was interactive (includes the lock screen)
 *   unlocked_ms   - after the keyguard was dismissed (excludes the lock screen)
 *   unlock_count  - how many times the device was unlocked
 *
 * charge_event records charger plug/unplug transitions. A "charge session" is the
 * span between an unplug and the next plug; the screen time over that span is the
 * "screen time since last charge" figure.
 */
public class Db extends SQLiteOpenHelper {

    public static final String NAME = "screentime.db";
    public static final int VERSION = 3;

    public Db(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE day_total ("
                + "day TEXT PRIMARY KEY,"
                + "total_ms INTEGER NOT NULL,"          // screen-on
                + "unlocked_ms INTEGER NOT NULL DEFAULT 0,"
                + "unlock_count INTEGER NOT NULL DEFAULT 0,"
                + "app_count INTEGER NOT NULL,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE app_usage ("
                + "day TEXT NOT NULL,"
                + "pkg TEXT NOT NULL,"
                + "total_ms INTEGER NOT NULL,"
                + "first_ts INTEGER NOT NULL,"
                + "last_ts INTEGER NOT NULL,"
                + "PRIMARY KEY (day, pkg))");
        db.execSQL("CREATE INDEX idx_app_usage_day ON app_usage(day)");
        createChargeTables(db);
    }

    private static void createChargeTables(SQLiteDatabase db) {
        // One row per charger transition. plugged: 1 = plugged in, 0 = unplugged.
        db.execSQL("CREATE TABLE IF NOT EXISTS charge_event ("
                + "ts INTEGER PRIMARY KEY,"             // wall clock, millis
                + "plugged INTEGER NOT NULL,"
                + "elapsed INTEGER NOT NULL DEFAULT 0," // boot-relative, for uptime checks
                + "source INTEGER NOT NULL DEFAULT 0)");// where the record came from
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_charge_event_ts ON charge_event(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // History is valuable - migrate additively, never drop.
        if (oldV < 2) {
            try { db.execSQL("ALTER TABLE day_total ADD COLUMN unlocked_ms INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignore) {}
            try { db.execSQL("ALTER TABLE day_total ADD COLUMN unlock_count INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignore) {}
        }
        if (oldV < 3) {
            createChargeTables(db);
        }
    }
}
