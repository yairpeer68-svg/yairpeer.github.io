package com.magen.family.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import com.magen.family.MagenApp;
import com.magen.family.R;

import java.util.Random;

/**
 * BlockedActivity — מסך "תוכן חסום".
 *
 * שיפורי חוויה (גל 5):
 *   • מסר מעודד מתחלף במקום שורה יבשה — הרגע הזה הוא הזדמנות, לא רק שגיאה.
 *   • השהיה מדורגת: ככל שהיו יותר חסימות היום, כפתור "חזרה" ננעל ליותר שניות
 *     (עד תקרה). ההשהיה נותנת רגע לנשום ומייקרת התמדה בפיתוי.
 */
public class BlockedActivity extends BaseActivity {

    private static final String[] MESSAGES = {
        "הרגע הזה יעבור. קח נשימה עמוקה.",
        "אתה חזק יותר מהפיתוי הזה.",
        "כל פעם שאתה עוצר — אתה מתחזק.",
        "זכור למה התחלת את הברית הזו.",
        "הרצף שלך שווה יותר מ-30 שניות.",
        "נשימה: שאף 4 · החזק 4 · נשוף 6.",
        "בחרת בזה. אתה שולט, לא הפיתוי."
    };

    private static final int MIN_DELAY_SEC = 3;
    private static final int MAX_DELAY_SEC = 15;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked);

        String blockedUrl = getIntent().getStringExtra("blocked_url");

        TextView tvUrl = findViewById(R.id.tv_blocked_url);
        if (tvUrl != null && blockedUrl != null) {
            String domain = blockedUrl
                .replace("https://", "")
                .replace("http://", "")
                .replace("www.", "");
            int slash = domain.indexOf('/');
            if (slash > 0) domain = domain.substring(0, slash);
            tvUrl.setText(domain);
        }

        // מסר מעודד מתחלף
        TextView tvMsg = findViewById(R.id.tv_blocked_msg);
        if (tvMsg != null) {
            tvMsg.setText(MESSAGES[new Random().nextInt(MESSAGES.length)]);
        }

        // כפתור "חזור" — עם השהיה מדורגת לפי כמות החסימות היום
        Button btnBack = findViewById(R.id.btn_go_home);
        if (btnBack != null) {
            View.OnClickListener goHome = v -> {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
                finish();
            };
            startProgressiveDelay(btnBack, goHome);
        }

        // כפתור "אני ההורה" - מבקש PIN ואז מאפשר גישה
        View btnParent = findViewById(R.id.btn_parent_override);
        if (btnParent != null) {
            btnParent.setOnClickListener(v -> {
                Intent pin = new Intent(this, PinActivity.class);
                pin.putExtra("mode", "verify");
                startActivityForResult(pin, 1);
            });
        }
    }

    /**
     * נועל את כפתור החזרה ל-N שניות, כש-N גדל עם מספר החסימות היום (עד תקרה).
     * הכפתור מציג ספירה לאחור ונפתח בסופה.
     */
    private void startProgressiveDelay(Button btn, View.OnClickListener onReady) {
        int todayBlocks = MagenApp.getInstance().getPrefs()
            .getInt(MagenApp.KEY_BLOCKED_TODAY, 0);
        int delay = Math.min(MIN_DELAY_SEC + todayBlocks, MAX_DELAY_SEC);

        final String label = getString(R.string.back);
        btn.setEnabled(false);
        final int[] remaining = { delay };

        Runnable tick = new Runnable() {
            @Override public void run() {
                if (remaining[0] <= 0) {
                    btn.setEnabled(true);
                    btn.setText(label);
                    btn.setOnClickListener(onReady);
                } else {
                    btn.setText(label + " (" + remaining[0] + ")");
                    remaining[0]--;
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            // שותף אימת - סגור את מסך החסימה
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        // מנע לחיצת BACK מהמסך הזה - חייבים ללחוץ "חזור הביתה"
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
    }
}
