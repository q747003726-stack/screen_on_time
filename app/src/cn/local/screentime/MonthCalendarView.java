package cn.local.screentime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Month grid where each day cell is shaded by its total screen time - the
 * calendar/heatmap style of the original app.
 *
 * Colour ramp: light green -> amber -> red as time grows, relative to a
 * reference "heavy day" (default 6h) so the scale adapts to the user.
 */
public class MonthCalendarView extends View {

    public interface OnDayClick { void onDay(String day); }

    private String ym = "";                       // yyyy-MM
    private final Map<String, Long> totals = new HashMap<>();
    private String selected = null;
    private OnDayClick listener;
    private long referenceMs = 6 * 3600_000L;     // full-intensity threshold

    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pWeek = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final String[] WEEK = {"一", "二", "三", "四", "五", "六", "日"};

    public MonthCalendarView(Context c) { super(c); init(); }
    public MonthCalendarView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pText.setTextAlign(Paint.Align.CENTER);
        pDim.setTextAlign(Paint.Align.CENTER);
        pWeek.setTextAlign(Paint.Align.CENTER);
        pSel.setStyle(Paint.Style.STROKE);
        pSel.setStrokeWidth(dp(2));
        pSel.setColor(Color.parseColor("#1A73E8"));
        setWillNotDraw(false);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setOnDayClick(OnDayClick l) { this.listener = l; }

    public void setMonth(String ym) { this.ym = ym; invalidate(); }

    public void setSelected(String day) { this.selected = day; invalidate(); }

    public void setData(Map<String, Long> data) {
        totals.clear();
        if (data != null) totals.putAll(data);
        // adapt the ramp so typical days still show contrast
        long max = 0;
        for (Long v : totals.values()) if (v != null && v > max) max = v;
        referenceMs = Math.max(2 * 3600_000L, max > 0 ? max : 6 * 3600_000L);
        invalidate();
    }

    private int daysInMonth() {
        try {
            String[] s = ym.split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(s[0]), Integer.parseInt(s[1]) - 1, 1);
            return c.getActualMaximum(Calendar.DAY_OF_MONTH);
        } catch (Exception e) {
            return 30;
        }
    }

    /** Monday-based weekday index (0=Mon) of the 1st. */
    private int firstWeekday() {
        try {
            String[] s = ym.split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(s[0]), Integer.parseInt(s[1]) - 1, 1);
            int dow = c.get(Calendar.DAY_OF_WEEK);      // 1=Sun..7=Sat
            return (dow + 5) % 7;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int rows = (int) Math.ceil((firstWeekday() + daysInMonth()) / 7.0);
        int cell = w / 7;
        int h = (int) (dp(30) + rows * cell + dp(8));
        setMeasuredDimension(w, h);
    }

    private RectF[] cells = new RectF[0];
    private String[] cellDay = new String[0];

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        float cell = w / 7f;
        float head = dp(28);
        int nDays = daysInMonth();
        int start = firstWeekday();
        int rows = (int) Math.ceil((start + nDays) / 7.0);

        pWeek.setTextSize(dp(12));
        pWeek.setColor(Color.parseColor("#8A8A8A"));
        for (int i = 0; i < 7; i++) {
            cv.drawText(WEEK[i], cell * i + cell / 2f, dp(16), pWeek);
        }

        cells = new RectF[rows * 7];
        cellDay = new String[rows * 7];
        pText.setTextSize(dp(13));

        for (int i = 0; i < rows * 7; i++) {
            int dayNum = i - start + 1;
            float x = cell * (i % 7);
            float y = head + cell * (i / 7);
            RectF r = new RectF(x + dp(3), y + dp(3), x + cell - dp(3), y + cell - dp(3));
            cells[i] = r;
            if (dayNum < 1 || dayNum > nDays) continue;

            String day = String.format(Locale.US, "%s-%02d", ym, dayNum);
            cellDay[i] = day;
            Long v = totals.get(day);

            pCell.setStyle(Paint.Style.FILL);
            pCell.setColor(shade(v == null ? -1 : v));
            cv.drawRoundRect(r, dp(6), dp(6), pCell);

            String label = String.valueOf(dayNum);
            pText.setColor(v == null ? Color.parseColor("#B0B0B0") : Color.parseColor("#202124"));
            cv.drawText(label, r.centerX(), r.centerY() + dp(5), pText);

            if (v != null && v > 0) {
                pDim.setTextSize(dp(9));
                pDim.setColor(Color.parseColor("#5F6368"));
                cv.drawText(Fmt.durShort(v), r.centerX(), r.bottom - dp(3), pDim);
            }
            if (day.equals(selected)) {
                cv.drawRoundRect(r, dp(6), dp(6), pSel);
            }
        }
    }

    /** -1 = no data; otherwise a green->amber->red ramp. */
    private int shade(long ms) {
        if (ms < 0) return Color.parseColor("#F1F3F4");
        float f = Math.min(1f, ms / (float) referenceMs);
        int r, g, b;
        if (f < 0.5f) {
            float t = f / 0.5f;
            r = lerp(0xC6, 0xFF, t);
            g = lerp(0xE8, 0xD5, t);
            b = lerp(0xC9, 0x8A, t);
        } else {
            float t = (f - 0.5f) / 0.5f;
            r = lerp(0xFF, 0xE8, t);
            g = lerp(0xD5, 0x6C, t);
            b = lerp(0x8A, 0x60, t);
        }
        return Color.rgb(r, g, b);
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null) {
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] != null && cellDay[i] != null && cells[i].contains(e.getX(), e.getY())) {
                    selected = cellDay[i];
                    invalidate();
                    listener.onDay(cellDay[i]);
                    return true;
                }
            }
        }
        return true;
    }
}
