package com.magen.family.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.magen.family.MagenApp;
import com.magen.family.R;
import com.magen.family.covenant.StreakManager;
import com.magen.family.stats.BlockStats;

/**
 * DashboardActivity — לוח מחוונים מקומי: רצף, חסימות, וגרף חסימות לפי שעה
 * עם תובנת דפוס. הגרף מצויר ב-Canvas (בלי ספריית תרשימים חיצונית — קטן
 * ומספיק לצרכים כאן).
 */
public class DashboardActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_light));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        // כרטיס מספרים ראשי
        LinearLayout summary = card();
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.addView(stat(getString(R.string.dash_streak),
            String.valueOf(StreakManager.currentDays(this))));
        summary.addView(stat(getString(R.string.dash_total_blocks),
            String.valueOf(MagenApp.getInstance().getPrefs()
                .getInt(MagenApp.KEY_BLOCKED_COUNT, 0))));
        int peak = BlockStats.peakHour(this);
        summary.addView(stat(getString(R.string.dash_peak_hour),
            peak < 0 ? "—" : String.format("%02d:00", peak)));
        root.addView(summary);

        // כותרת הגרף
        TextView chartTitle = new TextView(this);
        chartTitle.setText(R.string.dash_by_hour);
        chartTitle.setTextSize(16);
        chartTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        chartTitle.setPadding(0, dp(8), 0, dp(8));
        root.addView(chartTitle);

        // הגרף
        int[] hourly = BlockStats.getHourly(this);
        if (BlockStats.total(this) == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.dash_no_data);
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            root.addView(empty);
        } else {
            BarChartView chart = new BarChartView(this, hourly);
            chart.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
            root.addView(chart);

            // תובנה
            String insight = BlockStats.timeInsight(this);
            if (insight != null) {
                TextView tv = new TextView(this);
                tv.setText("💡 " + insight);
                tv.setTextColor(ContextCompat.getColor(this, R.color.accent_dark));
                tv.setBackgroundResource(R.drawable.bg_card_accent);
                int p = dp(12);
                tv.setPadding(p, p, p, p);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(12);
                tv.setLayoutParams(lp);
                root.addView(tv);
            }
        }
    }

    // ---------------- הגרף המצויר ----------------

    private static class BarChartView extends View {
        private final int[] data;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int max = 1;

        BarChartView(Context ctx, int[] data) {
            super(ctx);
            this.data = data;
            for (int v : data) if (v > max) max = v;
            bar.setColor(ContextCompat.getColor(ctx, R.color.accent));
            label.setColor(ContextCompat.getColor(ctx, R.color.text_muted));
            label.setTextSize(spToPx(ctx, 9));
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int n = data.length;               // 24
            float w = getWidth();
            float h = getHeight();
            float labelH = h * 0.12f;
            float chartH = h - labelH;
            float slot = w / n;
            float barW = slot * 0.6f;

            for (int i = 0; i < n; i++) {
                float ratio = max == 0 ? 0 : (float) data[i] / max;
                float barH = chartH * ratio;
                float left = i * slot + (slot - barW) / 2f;
                float top = chartH - barH;
                // צבע חם יותר לשעות שיא
                bar.setColor(ratio > 0.66f ? Color.parseColor("#EF4444")
                    : ratio > 0.33f ? Color.parseColor("#F59E0B")
                    : Color.parseColor("#5B6FE8"));
                c.drawRoundRect(left, top, left + barW, chartH, 4, 4, bar);

                // תווית לכל 3 שעות
                if (i % 3 == 0) {
                    c.drawText(String.format("%02d", i),
                        i * slot + slot / 2f - dpF(6), h - dpF(2), label);
                }
            }
        }

        private float dpF(int v) { return v * getResources().getDisplayMetrics().density; }
        private static float spToPx(Context ctx, int sp) {
            return sp * ctx.getResources().getDisplayMetrics().scaledDensity;
        }
    }

    // ---------------- עזרי UI ----------------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setBackgroundResource(R.drawable.bg_card);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout stat(String label, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(22);
        v.setTextColor(ContextCompat.getColor(this, R.color.accent));
        v.setGravity(Gravity.CENTER);
        col.addView(v);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        l.setGravity(Gravity.CENTER);
        col.addView(l);
        return col;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
