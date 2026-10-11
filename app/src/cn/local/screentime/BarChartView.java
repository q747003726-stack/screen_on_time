package cn.local.screentime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple horizontal bar chart of the last N days, oldest -> newest.
 */
public class BarChartView extends View {

    public interface OnBarClick { void onDay(String day); }

    private final List<String> days = new ArrayList<>();
    private final List<Long> vals = new ArrayList<>();
    private OnBarClick listener;
    private String selected;

    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBarSel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RectF[] bars = new RectF[0];

    public BarChartView(Context c) { super(c); init(); }
    public BarChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        pBar.setColor(Color.parseColor("#7BAF8E"));
        pBarSel.setColor(Color.parseColor("#1A73E8"));
        pTxt.setTextAlign(Paint.Align.CENTER);
        pGrid.setColor(Color.parseColor("#E8EAED"));
        pGrid.setStrokeWidth(dp(1));
        pTxt.setTextSize(dp(9));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setOnBarClick(OnBarClick l) { this.listener = l; }

    public void setSelected(String d) { selected = d; invalidate(); }

    public void setData(List<String> d, List<Long> v) {
        days.clear(); vals.clear();
        if (d != null) days.addAll(d);
        if (v != null) vals.addAll(v);
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int h = MeasureSpec.getSize(hSpec);
        if (h == 0) h = (int) dp(140);
        setMeasuredDimension(MeasureSpec.getSize(wSpec), h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int n = days.size();
        if (n == 0) return;
        int w = getWidth(), h = getHeight();
        float padBottom = dp(22), padTop = dp(14);
        float slot = w / (float) n;
        float barW = Math.max(dp(3), slot * 0.55f);

        long max = 1;
        for (Long v : vals) if (v != null && v > max) max = v;

        // baseline
        cv.drawLine(0, h - padBottom, w, h - padBottom, pGrid);

        bars = new RectF[n];
        pTxt.setColor(Color.parseColor("#5F6368"));
        for (int i = 0; i < n; i++) {
            long v = vals.get(i) == null ? 0 : vals.get(i);
            float bh = (h - padBottom - padTop) * (v / (float) max);
            float cx = slot * i + slot / 2f;
            RectF r = new RectF(cx - barW / 2f, h - padBottom - bh, cx + barW / 2f, h - padBottom);
            bars[i] = r;
            cv.drawRoundRect(r, dp(3), dp(3),
                    days.get(i).equals(selected) ? pBarSel : pBar);
            // label: day-of-month
            String d = days.get(i);
            String lbl = d.length() >= 10 ? d.substring(8) : d;
            cv.drawText(lbl, cx, h - dp(6), pTxt);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null) {
            for (int i = 0; i < bars.length; i++) {
                if (bars[i] != null && bars[i].contains(e.getX(), e.getY())) {
                    selected = days.get(i);
                    invalidate();
                    listener.onDay(days.get(i));
                    return true;
                }
            }
        }
        return true;
    }
}
