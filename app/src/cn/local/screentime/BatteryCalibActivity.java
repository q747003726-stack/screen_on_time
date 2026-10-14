package cn.local.screentime;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
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
 * Charge-counter calibration probe.
 *
 * The first probe showed CURRENT_NOW jumping wildly (517000 -> 1042000 uA in
 * 1.5s) while CHARGE_COUNTER only moved in 2000 uAh steps, and only once in two
 * seconds. Before we can use the counter to back-estimate "when was the charger
 * unplugged", we need to know its real resolution and noise.
 *
 * This samples the counter every 100 ms for ~20 s while the device is on battery
 * and reports:
 *   - the observed quantum (smallest non-zero delta)
 *   - how often it changes
 *   - a least-squares drain rate with an uncertainty estimate
 *   - the resulting time resolution for back-estimation
 */
public class BatteryCalibActivity extends Activity {

    private static final long DURATION_MS = 20000;
    private static final long INTERVAL_MS = 100;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final StringBuilder sb = new StringBuilder();
        final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        Intent batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        Utils.append(sb, "现在: " + fmt.format(new Date()));
        if (batt != null) {
            int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            Utils.append(sb, "status=" + status + "  plugged=" + plugged
                    + "  level=" + batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1));
            if (plugged != 0) {
                Utils.append(sb, "");
                Utils.append(sb, "!! 当前处于充电状态，请拔掉充电线后重新打开本页");
                Utils.append(sb, "   电荷计在校准期间必须处于放电状态。");
                render(sb);
                return;
            }
        }
        Utils.append(sb, "采样: 每 " + INTERVAL_MS + "ms 一次，共 " + (DURATION_MS / 1000) + " 秒");
        Utils.append(sb, "");

        int n = (int) (DURATION_MS / INTERVAL_MS);
        long[] t = new long[n];
        int[] c = new int[n];
        long t0 = android.os.SystemClock.elapsedRealtime();
        for (int i = 0; i < n; i++) {
            long target = t0 + i * INTERVAL_MS;
            long wait = target - android.os.SystemClock.elapsedRealtime();
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException ignore) {}
            }
            t[i] = android.os.SystemClock.elapsedRealtime() - t0;
            c[i] = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        }

        // ---- analysis ----------------------------------------------------
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int v : c) { if (v < lo) lo = v; if (v > hi) hi = v; }

        int quantum = Integer.MAX_VALUE;
        int changes = 0;
        for (int i = 1; i < n; i++) {
            int d = Math.abs(c[i] - c[i - 1]);
            if (d > 0) {
                changes++;
                if (d < quantum) quantum = d;
            }
        }

        Utils.append(sb, "[A] 基本统计");
        Utils.append(sb, "     样本数            = " + n);
        Utils.append(sb, "     charge 最小值     = " + lo + " uAh");
        Utils.append(sb, "     charge 最大值     = " + hi + " uAh");
        Utils.append(sb, "     期间下降          = " + (lo - hi >= 0 ? 0 : hi - lo) + " uAh"
                + "   (实际变化 " + (c[n - 1] - c[0]) + " uAh)");
        Utils.append(sb, "     变化次数          = " + changes + " / " + (n - 1));
        if (quantum != Integer.MAX_VALUE) {
            Utils.append(sb, "     最小非零变化(量化) = " + quantum + " uAh");
        } else {
            Utils.append(sb, "     最小非零变化       = 无（整个窗口没变）");
        }
        Utils.append(sb, "");

        Utils.append(sb, "[B] 线性拟合（放电速率）");
        double rate = 0;       // uAh per ms, positive means draining
        double rateErr = 0;
        if (changes >= 3) {
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int i = 0; i < n; i++) {
                sx += t[i]; sy += c[i]; sxx += (double) t[i] * t[i]; sxy += (double) t[i] * c[i];
            }
            double denom = n * sxx - sx * sx;
            double slope = denom == 0 ? 0 : (n * sxy - sx * sy) / denom;   // uAh per ms
            double intercept = (sy - slope * sx) / n;
            double ss = 0;
            for (int i = 0; i < n; i++) {
                double pred = intercept + slope * t[i];
                ss += (c[i] - pred) * (c[i] - pred);
            }
            double resid = Math.sqrt(ss / Math.max(1, n - 2));
            double xbar = sx / n;
            double sxxc = sxx - n * xbar * xbar;
            rateErr = sxxc > 0 ? resid / Math.sqrt(sxxc) : 0;
            rate = -slope;   // positive = draining
            Utils.append(sb, String.format(Locale.US, "     斜率              = %.4f uAh/ms  (= %.0f uA)", slope, slope * 1000));
            Utils.append(sb, String.format(Locale.US, "     拟合残差 σ        = %.1f uAh", resid));
            Utils.append(sb, String.format(Locale.US, "     放电速率          = %.0f uA  (误差 ±%.0f uA)", rate * 1000, rateErr * 1000));
            Utils.append(sb, String.format(Locale.US, "     换算              = %.0f mAh/小时", rate * 1000 / 1000 * 3600 / 1000));
        } else {
            Utils.append(sb, "     电荷计在 20 秒内几乎未变化 → 无法拟合");
            Utils.append(sb, "     说明它的更新周期较慢（可能 >20 秒）");
        }
        Utils.append(sb, "");

        Utils.append(sb, "[C] 对「反推拔线时刻」的意义");
        if (quantum != Integer.MAX_VALUE && rate > 0) {
            double msPerQuantum = quantum / rate;   // ms to burn one quantum
            Utils.append(sb, String.format(Locale.US,
                    "     消耗一个量化步长(%d uAh) 需要 %.0f 秒", quantum, msPerQuantum / 1000));
            Utils.append(sb, String.format(Locale.US,
                    "     → 反推精度上限约 ±%.0f 秒（理想线性假设下）", msPerQuantum / 1000));
            Utils.append(sb, "     实际还会受负载波动影响，误差更大");
        } else {
            Utils.append(sb, "     数据不足，无法估算精度");
            Utils.append(sb, "     → 倾向于不依赖电荷计反推，改用纯广播记录");
        }
        Utils.append(sb, "");

        Utils.append(sb, "[D] 原始序列（前 40 个样本）");
        for (int i = 0; i < Math.min(40, n); i++) {
            Utils.append(sb, String.format(Locale.US, "     %6d ms   %d", t[i], c[i]));
        }

        Utils.append(sb, "");
        Utils.append(sb, "=== 校准结束 ===");

        String path = "(未写入)";
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                File out = new File(dir, "battery_calib.txt");
                FileWriter w = new FileWriter(out, false);
                w.write(sb.toString());
                w.close();
                path = out.getAbsolutePath();
            }
        } catch (Throwable e) {
            path = "写入失败: " + e;
        }
        Utils.append(sb, "报告已写入: " + path);
        render(sb);
    }

    private void render(StringBuilder sb) {
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
}
