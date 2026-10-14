package cn.local.screentime;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Read-only battery probe.
 *
 * Goal: find out which battery data is actually reachable from a normal,
 * unrooted app, so we can decide whether "screen time since last unplug" is
 * feasible and how accurately it can be derived.
 *
 * Candidates examined:
 *   1. the sticky ACTION_BATTERY_CHANGED broadcast (level, status, plugged,
 *      voltage, temperature, health) plus its {@code EXTRA_*_TIME} fields
 *   2. BatteryManager.getIntProperty / getLongProperty for charge counter and
 *      instantaneous / average current
 *   3. the ACTION_POWER_CONNECTED / DISCONNECTED broadcasts that a receiver can
 *      listen for going forward
 *   4. whether the charge counter moves, which is what lets us back-estimate the
 *      unplug moment after the fact
 *
 * Writes its report to the app's external files dir so it can be picked up with
 * adb without any storage permission:
 *   /sdcard/Android/data/cn.local.screentime/files/battery_probe.txt
 */
public class BatteryProbeActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        Utils.append(sb, "现在: " + fmt.format(new Date(now)));
        Utils.append(sb, "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + "  Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Utils.append(sb, "");

        // ---- 1) sticky battery broadcast -------------------------------
        Utils.append(sb, "[1] ACTION_BATTERY_CHANGED（粘性广播）");
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent it = registerReceiver(null, f);
            if (it == null) {
                Utils.append(sb, "     接收不到（返回 null）");
            } else {
                Utils.append(sb, "     level            = " + it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        + " / " + it.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
                int status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                Utils.append(sb, "     status           = " + status + "  " + statusName(status));
                int plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                Utils.append(sb, "     plugged          = " + plugged + "  " + pluggedName(plugged));
                Utils.append(sb, "     voltage(mV)      = " + it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
                Utils.append(sb, "     temperature(0.1C)= " + it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE));
                Utils.append(sb, "     health           = " + it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1));
                Utils.append(sb, "     technology       = " + it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
                Utils.append(sb, "     present          = " + it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false));
                // The documented *_TIME extras are only meaningful on some ROMs.
                Utils.append(sb, "     -- 时间类 EXTRA（多半为 0 或不存在）--");
                extraTime(sb, it, fmt, "EXTRA_CHARGE_TIME_REMAINING",
                        "android.os.extra.CHARGE_TIME_REMAINING");
                extraTime(sb, it, fmt, "EXTRA_DISCHARGE_TIME_REMAINING",
                        "android.os.extra.DISCHARGE_TIME_REMAINING");
            }
        } catch (Throwable t) {
            Utils.append(sb, "     异常: " + t);
        }
        Utils.append(sb, "");

        // ---- 2) BatteryManager properties ------------------------------
        Utils.append(sb, "[2] BatteryManager 属性");
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm == null) {
            Utils.append(sb, "     BATTERY_SERVICE 不可用");
        } else {
            prop(sb, bm, "CHARGE_COUNTER (uAh)", BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            prop(sb, bm, "CAPACITY (%)", BatteryManager.BATTERY_PROPERTY_CAPACITY);
            prop(sb, bm, "CURRENT_NOW (uA)", BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            prop(sb, bm, "CURRENT_AVERAGE (uA)", BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
            if (Build.VERSION.SDK_INT >= 21) {
                prop(sb, bm, "ENERGY_COUNTER (nWh)", BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
            }
            try {
                Utils.append(sb, "     isCharging()     = " + bm.isCharging());
            } catch (Throwable t) {
                Utils.append(sb, "     isCharging() 异常: " + t);
            }
        }
        Utils.append(sb, "");

        // ---- 3) charge counter stability (back-estimation feasibility) --
        Utils.append(sb, "[3] 电荷计采样（500ms 间隔，共 5 次）");
        if (bm != null) {
            for (int i = 0; i < 5; i++) {
                int cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                Utils.append(sb, "     t+" + (i * 500) + "ms  charge=" + cc + " uAh   current=" + cur + " uA");
                if (i < 4) {
                    try { Thread.sleep(500); } catch (InterruptedException ignore) {}
                }
            }
        }
        Utils.append(sb, "");

        // ---- 4) what a receiver can observe going forward --------------
        Utils.append(sb, "[4] 可持续监听的动作（需注册接收器，从注册时起记录）");
        Utils.append(sb, "     ACTION_POWER_CONNECTED      = " + Intent.ACTION_POWER_CONNECTED);
        Utils.append(sb, "     ACTION_POWER_DISCONNECTED   = " + Intent.ACTION_POWER_DISCONNECTED);
        Utils.append(sb, "     ACTION_BATTERY_CHANGED      = " + Intent.ACTION_BATTERY_CHANGED);
        Utils.append(sb, "     ACTION_BATTERY_LOW          = " + Intent.ACTION_BATTERY_LOW);
        Utils.append(sb, "     ACTION_BOOT_COMPLETED       = " + Intent.ACTION_BOOT_COMPLETED);
        Utils.append(sb, "     说明: 前两个是普通广播，可在 Manifest 静态注册，重启后仍生效");
        Utils.append(sb, "");

        // ---- 5) persistence of a derived unplug time -------------------
        Utils.append(sb, "[5] 系统运行时间（用于判定重启导致的时间轴断裂）");
        Utils.append(sb, "     elapsedRealtime   = " + android.os.SystemClock.elapsedRealtime() + " ms");
        Utils.append(sb, "     uptimeMillis      = " + android.os.SystemClock.uptimeMillis() + " ms");
        Utils.append(sb, "     currentTimeMillis = " + now);
        Utils.append(sb, "     → 两者差值 = 本次开机时刻 ≈ "
                + fmt.format(new Date(now - android.os.SystemClock.elapsedRealtime())));
        Utils.append(sb, "");
        Utils.append(sb, "=== 探针结束 ===");

        // ---- persist for adb retrieval --------------------------------
        String path = "(未写入)";
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                File out = new File(dir, "battery_probe.txt");
                FileWriter w = new FileWriter(out, false);
                w.write(sb.toString());
                w.close();
                path = out.getAbsolutePath();
            }
        } catch (Throwable t) {
            path = "写入失败: " + t;
        }
        Utils.append(sb, "报告已写入: " + path);

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setPadding(24, 24, 24, 24);
        tv.setTextIsSelectable(true);
        ScrollView sc = new ScrollView(this);
        sc.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sc);
        Insets.apply(this, tv);
    }

    private static void prop(StringBuilder sb, BatteryManager bm, String label, int id) {
        try {
            int v = bm.getIntProperty(id);
            Utils.append(sb, String.format(Locale.US, "     %-24s = %d", label, v));
        } catch (Throwable t) {
            Utils.append(sb, String.format(Locale.US, "     %-24s = 异常 %s", label, t.getClass().getSimpleName()));
        }
    }

    private static void extraTime(StringBuilder sb, Intent it, SimpleDateFormat fmt, String label, String key) {
        try {
            long v = it.getLongExtra(key, -1);
            String human = v > 0 ? fmt.format(new Date(v)) : "(无/0)";
            Utils.append(sb, "     " + label + " = " + v + "  " + human);
        } catch (Throwable t) {
            Utils.append(sb, "     " + label + " = 异常");
        }
    }

    private static String statusName(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "CHARGING";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "DISCHARGING";
            case BatteryManager.BATTERY_STATUS_FULL: return "FULL";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "NOT_CHARGING";
            case BatteryManager.BATTERY_STATUS_UNKNOWN: return "UNKNOWN";
            default: return "?";
        }
    }

    private static String pluggedName(int p) {
        switch (p) {
            case 0: return "未充电";
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "无线";
            default: return "?";
        }
    }
}
