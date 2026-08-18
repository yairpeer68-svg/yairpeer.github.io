package com.magen.family.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.magen.family.MagenApp;

/**
 * FloatingBadgeService — "מדבקה צפה" שמראה שההגנה פעילה.
 *
 * badge קטן ("🛡️ מוגן") שיושב בפינת המסך מעל כל האפליקציות:
 *   • אינדיקציה ויזואלית מתמדת שההגנה חיה (גם פסיכולוגית — "מישהו רואה").
 *   • אי אפשר לסגור/להזיז אותו בלי PIN הורים — לחיצה עליו פותחת אימות PIN,
 *     ורק אחרי אימות אפשר להסתיר זמנית.
 *   • foreground service — עמיד יותר מפני עצירה ע"י המערכת.
 *   • לא חוסם מגע: ה-overlay מוגדר NOT_TOUCHABLE חוץ מה-badge עצמו,
 *     כך שהוא לא מפריע לשימוש הרגיל במכשיר.
 *
 * דורש הרשאת "הצגה מעל אפליקציות" (SYSTEM_ALERT_WINDOW) — כבר קיימת אצלך.
 * להצהיר ב-Manifest (ראה הערה למטה) ולהפעיל מ-ServiceRevival/MagenApp.
 */
public class FloatingBadgeService extends android.app.Service {

    private static final String TAG = "FloatingBadge";
    private static final String CHANNEL_ID = "magen_badge";
    private static final int NOTIF_ID = 4711;

    public static volatile boolean isShowing = false;

    private WindowManager wm;
    private View badgeView;
    private WindowManager.LayoutParams params;

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        if (!Settings.canDrawOverlays(this)) {
            android.util.Log.w(TAG, "No overlay permission — stopping idle FGS");
            stopSelf();
            return;
        }
        try {
            showBadge();
        } catch (Exception e) {
            // אם אין הרשאת overlay — לא מקריס, פשוט לא מציג
            android.util.Log.e(TAG, "showBadge failed: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // אם התבקש להציג מחדש (למשל אחרי אימות שהסתיר זמנית)
        if (intent != null && "SHOW".equals(intent.getAction()) && !isShowing) {
            try { showBadge(); } catch (Exception ignored) {}
        }
        return START_STICKY;
    }

    // ---------------- ה-badge עצמו ----------------

    private void showBadge() {
        if (isShowing) return;
        if (!Settings.canDrawOverlays(this)) {
            android.util.Log.w(TAG, "No overlay permission");
            return;
        }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        LinearLayout badge = new LinearLayout(this);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(dp(10), dp(6), dp(12), dp(6));

        // רקע מעוגל חצי-שקוף
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC1E2A4A"));   // כחול כהה, ~80% אטימות
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.parseColor("#5C6BC0"));
        badge.setBackground(bg);

        TextView shield = new TextView(this);
        shield.setText("🛡️");
        shield.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        badge.addView(shield);

        TextView label = new TextView(this);
        label.setText("מוגן");
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(dp(6), 0, 0, 0);
        badge.addView(label);

        // לחיצה על ה-badge — דורשת PIN כדי להסתיר זמנית
        badge.setOnClickListener(v -> requestPinToHide());

        badgeView = badge;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // FLAG_NOT_FOCUSABLE — לא גונב מיקוד/מקלדת.
            // (לא שמים NOT_TOUCHABLE כי אנחנו כן רוצים לתפוס לחיצה על ה-badge)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(48);

        wm.addView(badgeView, params);
        isShowing = true;
        android.util.Log.d(TAG, "Badge shown");
    }

    /** לחיצה על ה-badge -> פותח מסך אימות PIN. הסתרה זמנית רק אחרי אימות. */
    private void requestPinToHide() {
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), getPackageName() + ".ui.PinActivity");
            i.putExtra("purpose", "hide_badge");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.e(TAG, "open PinActivity failed: " + e.getMessage());
        }
    }

    private void removeBadge() {
        try {
            if (wm != null && badgeView != null && isShowing) {
                wm.removeView(badgeView);
            }
        } catch (Exception ignored) {}
        isShowing = false;
        badgeView = null;
    }

    // ---------------- foreground service ----------------

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "הגנת שומר הברית", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Intent open = new Intent();
        open.setClassName(getPackageName(), getPackageName() + ".ui.MainActivity");
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? CHANNEL_ID : null)
            .setContentTitle("שומר הברית פעיל")
            .setContentText("ההגנה פועלת ברקע")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();

        startForeground(NOTIF_ID, n);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        removeBadge();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
