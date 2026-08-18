package com.magen.family.visual;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Immediate opaque privacy curtain shown before navigating away from visual content. */
public final class MagenVisualCurtain {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WindowManager wm;
    private static FrameLayout view;
    private static boolean showing;
    private static final Runnable SAFETY_HIDE = MagenVisualCurtain::hide;

    private MagenVisualCurtain() {}

    public static boolean isShowing() { return showing; }

    public static void show(AccessibilityService service, String label) {
        if (service == null) return;
        Runnable action = () -> {
            try {
                if (showing) {
                    MAIN.removeCallbacks(SAFETY_HIDE);
                    MAIN.postDelayed(SAFETY_HIDE, 5000);
                    return;
                }
                wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
                view = new FrameLayout(service);
                view.setBackgroundColor(Color.rgb(11, 16, 24));
                view.setOnTouchListener((v, e) -> true);

                LinearLayout box = new LinearLayout(service);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                int pad = dp(service, 24);
                box.setPadding(pad, pad, pad, pad);

                TextView shield = new TextView(service);
                shield.setText("🛡️"); shield.setTextSize(42); shield.setGravity(Gravity.CENTER);
                TextView title = new TextView(service);
                title.setText("Magen הסתיר תוכן חזותי"); title.setTextColor(Color.WHITE);
                title.setTextSize(24); title.setGravity(Gravity.CENTER);
                TextView sub = new TextView(service);
                sub.setText("זוהה תוכן שאינו מתאים להגנה הפעילה");
                sub.setTextColor(Color.LTGRAY); sub.setTextSize(16); sub.setGravity(Gravity.CENTER);

                box.addView(shield); box.addView(title); box.addView(sub);
                view.addView(box, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                wm.addView(view, lp);
                showing = true;
                MAIN.removeCallbacks(SAFETY_HIDE);
                MAIN.postDelayed(SAFETY_HIDE, 5000);
            } catch (Exception ignored) {
                showing = false;
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else MAIN.post(action);
    }

    public static void hide() {
        MAIN.post(() -> {
            MAIN.removeCallbacks(SAFETY_HIDE);
            try {
                if (showing && wm != null && view != null) wm.removeView(view);
            } catch (Exception ignored) {}
            showing = false; view = null; wm = null;
        });
    }

    private static int dp(AccessibilityService c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
