package cn.local.screentime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Records charger plug/unplug transitions.
 *
 * Registered statically in the manifest, so the system starts this process only
 * when one of the two broadcasts arrives. There is no service, no foreground
 * notification and no alarm involved: between events the app does not exist.
 *
 * The broadcast carries no timestamp, so the moment is taken when we run. Under
 * Doze delivery can be deferred, which makes the recorded unplug slightly late
 * and the "screen time since unplug" slightly low; there is no API that exposes
 * the true transition time.
 */
public class PowerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        final int plugged;
        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            plugged = 1;
        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            plugged = 0;
        } else {
            return;
        }
        // Receiver work must be quick; the database write is small but do it off
        // the main thread anyway.
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    new ChargeRepo(app).record(plugged, ChargeRepo.SRC_BROADCAST);
                } catch (Throwable t) {
                    android.util.Log.w("ScreenTime", "power record failed: " + t);
                } finally {
                    try { pr.finish(); } catch (Throwable ignore) {}
                }
            }
        }).start();
    }
}
