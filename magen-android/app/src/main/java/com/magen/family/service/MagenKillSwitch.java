package com.magen.family.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.magen.family.ui.PinActivity;

/**
 * KillSwitch — מסך חסימה. תומך בשני מצבים:
 *  1. נעילת צינון לזמן קצוב (lockout_minutes) — עם ספירה לאחור, נפתח אוטומטית בסוף.
 *  2. נעילה עד הזנת PIN (ברירת מחדל, אם אין דקות).
 * זמן הסיום נשמר ב-SharedPreferences כדי לשרוד restart של השירות.
 */
public class MagenKillSwitch extends Service {

    private static final String PREFS = "magen_killswitch";
    private static final String KEY_UNLOCK_AT  = "unlock_at";
    /** נעילה שנפתחת רק ב-PIN (בלי טיימר). חייבת להישמר כדי לשרוד יציאה מהמסך. */
    private static final String KEY_REQUIRE_PIN = "require_pin";

    private WindowManager wm;
    private LinearLayout overlay;
    private TextView timerText;
    private static volatile boolean isShowing = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long unlockAt = 0;
    private Runnable ticker;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        int minutes = intent != null ? intent.getIntExtra("lockout_minutes", 0) : 0;
        boolean requirePin = intent != null && intent.getBooleanExtra("require_pin", false);

        if (minutes > 0) {
            // נעילת צינון חדשה עם טיימר
            unlockAt = System.currentTimeMillis() + minutes * 60_000L;
            sp.edit().putLong(KEY_UNLOCK_AT, unlockAt).apply();
        } else {
            // נעילה עד PIN — הדגל חייב להישמר, אחרת יציאה מהמסך פותחת את המכשיר
            if (requirePin) {
                sp.edit().putBoolean(KEY_REQUIRE_PIN, true).apply();
            }
            unlockAt = sp.getLong(KEY_UNLOCK_AT, 0);
        }

        if (!isShowing) show();
        startTicker();
        return START_STICKY;
    }

    private void show() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        overlay = new LinearLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#F2000000"));
        overlay.setGravity(Gravity.CENTER);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setOnTouchListener((v, ev) -> true); // חוסם מגעים

        TextView icon = new TextView(this);
        icon.setText("🛡️");
        icon.setTextSize(72);
        icon.setGravity(Gravity.CENTER);
        overlay.addView(icon);

        TextView title = new TextView(this);
        title.setText("רגע של עצירה");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 20, 0, 8);
        overlay.addView(title);

        TextView msg = new TextView(this);
        // משפט חיזוק אישי (מסונכרן מטלגרם) — קופץ ברגע הנפילה
        msg.setText(FallSentences.getRandom(this));
        msg.setTextSize(17);
        msg.setTextColor(Color.parseColor("#CCCCCC"));
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(50, 0, 50, 24);
        overlay.addView(msg);

        timerText = new TextView(this);
        timerText.setTextSize(48);
        timerText.setTextColor(Color.parseColor("#7B8FF8"));
        timerText.setGravity(Gravity.CENTER);
        timerText.setPadding(0, 0, 0, 24);
        overlay.addView(timerText);

        Button pinBtn = new Button(this);
        pinBtn.setText("פתיחה עם קוד");
        pinBtn.setOnClickListener(v -> {
            // מסירים את ה-overlay כדי שמסך ה-PIN ייראה. הנעילה עצמה נשארת
            // רשומה ב-prefs (unlock_at / require_pin), ולכן יציאה בלי אימות
            // מחזירה אותה — ראה PinActivity.rearmKillSwitch.
            Intent i = new Intent(MagenKillSwitch.this, PinActivity.class);
            i.putExtra("mode", "verify");
            i.putExtra("killswitch", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            try {
                if (wm != null && overlay != null && isShowing) wm.removeView(overlay);
            } catch (Exception ignored) {}
            isShowing = false;
            stopSelf();
        });
        overlay.addView(pinBtn);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        );
        params.gravity = Gravity.CENTER;

        try {
            wm.addView(overlay, params);
            isShowing = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startTicker() {
        if (ticker != null) handler.removeCallbacks(ticker);
        ticker = new Runnable() {
            @Override public void run() {
                long remaining = unlockAt - System.currentTimeMillis();
                if (unlockAt > 0 && remaining <= 0) {
                    // נגמר הזמן — פתח
                    clearAndStop();
                    return;
                }
                if (timerText != null) {
                    if (unlockAt > 0) {
                        long sec = remaining / 1000;
                        timerText.setText(String.format("%02d:%02d", sec / 60, sec % 60));
                    } else {
                        timerText.setText("");
                    }
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(ticker);
    }

    private void clearAndStop() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .remove(KEY_UNLOCK_AT)
            .remove(KEY_REQUIRE_PIN)
            .apply();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (ticker != null) handler.removeCallbacks(ticker);
        try {
            if (wm != null && overlay != null && isShowing) wm.removeView(overlay);
        } catch (Exception ignored) {}
        isShowing = false;
        super.onDestroy();
    }

    public static boolean isActive() { return isShowing; }
}
