package cn.local.screentime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fold the system's usage buckets into our own history on boot, so the local
 * database keeps filling up even if the app is rarely opened.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        // Re-arm the daily archive alarm; alarms do not survive a reboot.
        ArchiveReceiver.schedule(app);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    UsageRepo r = new UsageRepo(app);
                    if (r.hasPermission()) r.refresh();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }).start();
    }
}
