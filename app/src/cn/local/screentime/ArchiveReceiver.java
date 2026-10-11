package cn.local.screentime;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * Daily background archival.
 *
 * The system only keeps roughly two days of raw usage events, so if the app is
 * not opened for several days the days in between become unreadable forever.
 * Archiving on a schedule - rather than only when the UI happens to be opened -
 * is what keeps the history continuous.
 *
 * Note: Doze can defer these alarms. A once-a-day coarse alarm is delivered once
 * the device wakes from Doze, which is good enough to avoid losing days; the
 * refresh is also cheap and idempotent, so running it more often is harmless.
 */
public class ArchiveReceiver extends BroadcastReceiver {

    private static final int REQ = 0x5C7E;

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        final boolean reschedule = intent == null
                || !"cn.local.screentime.ACTION_ARCHIVE".equals(intent.getAction());
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    UsageRepo r = new UsageRepo(app);
                    if (r.hasPermission()) {
                        int n = r.refresh();
                        android.util.Log.i("ScreenTime", "scheduled archive wrote " + n + " days");
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                schedule(app);
            }
        }).start();
    }

    /** Schedule (or reschedule) the next daily archive run. */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent i = new Intent(ctx, ArchiveReceiver.class);
            i.setAction("cn.local.screentime.ACTION_ARCHIVE");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ, i, flags);

            // Next 00:30 local time - after midnight so the whole day is closed out.
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 30);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long at = c.getTimeInMillis();

            // setAndAllowWhileIdle survives Doze (subject to its own rate limits);
            // fall back to inexact repeating on older platforms.
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at,
                        AlarmManager.INTERVAL_DAY, pi);
            }
            android.util.Log.i("ScreenTime", "archive alarm scheduled for " + new java.util.Date(at));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
