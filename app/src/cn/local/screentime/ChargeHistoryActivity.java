package cn.local.screentime;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Charging history: one row per discharge cycle, newest first.
 *
 * A cycle is the span from unplugging the charger to plugging it back in, and the
 * figure shown is the screen-on time within that span.
 */
public class ChargeHistoryActivity extends Activity {

    private ChargeRepo charge;
    private LinearLayout list;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        charge = new ChargeRepo(this);
        View root = buildUi();
        setContentView(root);
        Insets.apply(this, root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(16);
        bar.setPadding(pad, pad, pad, dp(8));

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(22);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        TextView title = new TextView(this);
        title.setText("充电记录");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(back);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, 0, pad, pad);
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setTextSize(13);
        empty.setTextColor(Color.parseColor("#5F6368"));
        empty.setPadding(pad, dp(4), pad, pad);
        root.addView(empty);
        return root;
    }

    private void render() {
        list.removeAllViews();
        List<ChargeRepo.Cycle> cycles = charge.cycles();

        if (cycles.isEmpty()) {
            empty.setText("还没有充电记录。\n\n拔掉充电线后，这里会开始记录。");
            return;
        }
        empty.setText("共 " + cycles.size() + " 次记录 · 每次「拔线 → 再插线」为一条");

        for (ChargeRepo.Cycle cy : cycles) {
            list.addView(row(cy));
        }
    }

    private View row(ChargeRepo.Cycle cy) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        TextView head = new TextView(this);
        String label = Fmt.dayLabel(UsageRepo.dayKey(cy.startTs)) + "  " + Fmt.hhmm(cy.startTs);
        head.setText(label + (cy.estimated ? "（估算起点）" : ""));
        head.setTextSize(13);
        head.setTextColor(Color.parseColor("#5F6368"));
        card.addView(head);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.BOTTOM);
        mid.setPadding(0, dp(6), 0, 0);

        TextView big = new TextView(this);
        big.setText(Fmt.dur(cy.screenOnMs));
        big.setTextSize(26);
        big.setTextColor(Color.parseColor("#202124"));
        mid.addView(big);

        TextView unit = new TextView(this);
        unit.setText(cy.ongoing ? "   进行中" : "   已结束");
        unit.setTextSize(12);
        unit.setTextColor(cy.ongoing ? Color.parseColor("#1A73E8") : Color.parseColor("#8A8A8A"));
        unit.setPadding(0, 0, 0, dp(4));
        mid.addView(unit);
        card.addView(mid);

        TextView sub = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append("亮屏时长");
        if (cy.unlockCount > 0) sb.append(" · 解锁 ").append(cy.unlockCount).append(" 次");
        if (cy.screenOnCalibratedTs == 0 && !cy.estimated) {
            sb.append(" · 系统事件只保留约 2 天，此值可能偏小");
        }
        if (cy.estimated) {
            sb.append(" · 起点为估算，仅供参照");
        }
        sub.setText(sb.toString());
        sub.setTextSize(11);
        sub.setTextColor(Color.parseColor("#8A8A8A"));
        sub.setPadding(0, dp(4), 0, 0);
        card.addView(sub);

        // span detail
        TextView span = new TextView(this);
        String endTxt = cy.ongoing ? "现在" : Fmt.hhmm(cy.endTs);
        long spanMs = Math.max(0, cy.endTs - cy.startTs);
        span.setText("区间 " + Fmt.hhmm(cy.startTs) + " → " + endTxt
                + "（共 " + Fmt.dur(spanMs) + "）");
        span.setTextSize(11);
        span.setTextColor(Color.parseColor("#9AA0A6"));
        span.setPadding(0, dp(2), 0, 0);
        card.addView(span);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Open the per-app breakdown for this discharge cycle's window, not
                // for the whole calendar day it happens to start on.
                Intent i = new Intent(ChargeHistoryActivity.this, DayDetailActivity.class);
                i.putExtra(DayDetailActivity.EXTRA_FROM, cy.startTs);
                i.putExtra(DayDetailActivity.EXTRA_TO, cy.endTs);
                i.putExtra(DayDetailActivity.EXTRA_TITLE, "本次充电后");
                startActivity(i);
            }
        });
        return card;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
