package com.magen.family.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.magen.family.debug.DebugLog;

/**
 * DebugActivity — מסך אבחון: מצב ההגנה כרגע + יומן האירועים, עם כפתור
 * שיתוף/העתקה. זה מה שמאפשר לאבחן תקלה במכשיר בלי לחבר אותו למחשב.
 */
public class DebugActivity extends BaseActivity {

    private TextView body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#12151F"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("🔍 אבחון");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("מצב ההגנה ויומן האירועים. לחץ \"שתף\" כדי לשלוח לבדיקה.");
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#B9C0D4"));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        body = new TextView(this);
        body.setTextSize(12);
        body.setTextColor(Color.parseColor("#E6E9F2"));
        body.setBackgroundColor(Color.parseColor("#1E2333"));
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);
        root.addView(body);

        root.addView(button("↗ שתף את הדוח", dp(18), v -> {
            try {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                s.putExtra(Intent.EXTRA_SUBJECT, "שומר הברית — דוח אבחון");
                s.putExtra(Intent.EXTRA_TEXT, report());
                startActivity(Intent.createChooser(s, "שתף דוח אבחון"));
            } catch (Exception e) {
                Toast.makeText(this, "השיתוף נכשל", Toast.LENGTH_SHORT).show();
            }
        }));

        root.addView(button("📋 העתק", dp(8), v -> {
            try {
                ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("magen debug", report()));
                Toast.makeText(this, "הועתק ✓", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "ההעתקה נכשלה", Toast.LENGTH_SHORT).show();
            }
        }));

        root.addView(button("🔄 רענן", dp(8), v -> refresh()));

        root.addView(button("🗑 נקה יומן", dp(8), v -> {
            DebugLog.clear(this);
            refresh();
        }));

        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    private String report() {
        DebugLog.flush(this);
        return DebugLog.buildReport(this);
    }

    private void refresh() {
        body.setText(report());
    }

    private Button button(String text, int topMargin, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
