package com.magen.family.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * CrashActivity — מסך "מה קרה" שקופץ בכל קריסה.
 *
 * למה זה קיים:
 *   כשמשתמש מדווח "האפליקציה קורסת" אין לנו שום דרך לדעת *למה* בלי לחבר
 *   את הטלפון למחשב ולקרוא logcat. המסך הזה מציג את הדוח המלא — סוג
 *   התקלה, המקום המדויק בקוד, ופרטי המכשיר — ומאפשר להעתיק/לשתף אותו
 *   בלחיצה אחת. כך אפשר לאבחן תקלה מרחוק.
 *
 * חשוב — למה תהליך נפרד (android:process=":crash"):
 *   ברגע קריסה התהליך של האפליקציה גוסס. אקטיביטי שנפתחת באותו תהליך
 *   הייתה מתה יחד איתו ולא הייתה מוצגת. תהליך נפרד שורד ומציג את הדוח.
 */
public class CrashActivity extends Activity {

    public static final String EXTRA_REPORT = "crash_report";

    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        report = getIntent() != null ? getIntent().getStringExtra(EXTRA_REPORT) : null;
        if (report == null) report = "לא נמצא דוח.";

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#12151F"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(36), pad, pad);

        TextView icon = new TextView(this);
        icon.setText("⚠️");
        icon.setTextSize(48);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);

        TextView title = new TextView(this);
        title.setText("האפליקציה נתקלה בתקלה");
        title.setTextSize(23);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(6));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("זו גרסת בדיקה, ולכן אנחנו מראים לך בדיוק מה קרה.\n"
                  + "לחץ \"העתק\" ושלח את הטקסט — כך אפשר לתקן במדויק.");
        sub.setTextSize(14);
        sub.setTextColor(Color.parseColor("#B9C0D4"));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(18));
        root.addView(sub);

        TextView body = new TextView(this);
        body.setText(report);
        body.setTextSize(12);
        body.setTextColor(Color.parseColor("#E6E9F2"));
        body.setBackgroundColor(Color.parseColor("#1E2333"));
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(body);

        Button copy = new Button(this);
        copy.setAllCaps(false);
        copy.setText("📋 העתק את הדוח");
        copy.setLayoutParams(wide(dp(18)));
        copy.setOnClickListener(v -> {
            try {
                ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("magen crash", report));
                Toast.makeText(this, "הדוח הועתק ✓", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "ההעתקה נכשלה", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copy);

        Button share = new Button(this);
        share.setAllCaps(false);
        share.setText("↗ שתף את הדוח");
        share.setLayoutParams(wide(dp(8)));
        share.setOnClickListener(v -> {
            try {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                s.putExtra(Intent.EXTRA_SUBJECT, "שומר הברית — דוח תקלה");
                s.putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(s, "שתף דוח"));
            } catch (Exception e) {
                Toast.makeText(this, "השיתוף נכשל", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(share);

        Button close = new Button(this);
        close.setAllCaps(false);
        close.setText("סגור");
        close.setLayoutParams(wide(dp(8)));
        close.setOnClickListener(v -> {
            finish();
            // התהליך הראשי כבר מת; סוגרים גם את תהליך הקריסה
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        root.addView(close);

        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout.LayoutParams wide(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
