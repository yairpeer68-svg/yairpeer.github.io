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
    private static boolean autoSkipMode;
    private static final Runnable SAFETY_HIDE = MagenVisualCurtain::hide;

    private MagenVisualCurtain() {}

    public static boolean isShowing() { return showing; }
    public static boolean isAutoSkipMode() { return showing && autoSkipMode; }

    public static void show(AccessibilityService service, String label) {
        showInternal(service,
            "Magen הסתיר תוכן חזותי",
            "זוהה תוכן שאינו מתאים להגנה הפעילה",
            true,
            false,
            5_000L);
    }

    /**
     * Short-form feed mode: hide the unsafe frame while allowing the one-shot accessibility
     * gesture to pass through to the underlying app. The overlay is intentionally NOT touchable.
     */
    public static void showAutoSkip(AccessibilityService service, String label) {
        showInternal(service,
            "Magen מדלג על התוכן",
            "זוהה תוכן חריג — עוברים לפריט הבא פעם אחת",
            false,
            true,
            1_200L);
    }

    private static void showInternal(AccessibilityService service, String titleText, String subText,
                                     boolean blockTouches, boolean skipMode, long safetyHideMs) {
        if (service == null) return;
        Runnable action = () -> {
            try {
                // Recreate when switching between blocking and pass-through modes because the
                // WindowManager flags are security-significant.
                if (showing && autoSkipMode != skipMode) removeNow();
                if (showing) {
                    MAIN.removeCallbacks(SAFETY_HIDE);
                    MAIN.postDelayed(SAFETY_HIDE, safetyHideMs);
                    return;
                }

                wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
                view = new FrameLayout(service);
                view.setBackgroundColor(Color.rgb(11, 16, 24));
                if (blockTouches) view.setOnTouchListener((v, e) -> true);

                LinearLayout box = new LinearLayout(service);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                int pad = dp(service, 24);
                box.setPadding(pad, pad, pad, pad);

                TextView shield = new TextView(service);
                shield.setText("🛡️"); shield.setTextSize(42); shield.setGravity(Gravity.CENTER);
                TextView title = new TextView(service);
                title.setText(titleText); title.setTextColor(Color.WHITE);
                title.setTextSize(24); title.setGravity(Gravity.CENTER);
                TextView sub = new TextView(service);
                sub.setText(subText);
                sub.setTextColor(Color.LTGRAY); sub.setTextSize(16); sub.setGravity(Gravity.CENTER);

                box.addView(shield); box.addView(title); box.addView(sub);
                view.addView(box, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

                int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (!blockTouches) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.OPAQUE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                wm.addView(view, lp);
                showing = true;
                autoSkipMode = skipMode;
                MAIN.removeCallbacks(SAFETY_HIDE);
                MAIN.postDelayed(SAFETY_HIDE, safetyHideMs);
            } catch (Exception ignored) {
                showing = false;
                autoSkipMode = false;
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else MAIN.post(action);
    }

    public static void hide() {
        MAIN.post(MagenVisualCurtain::removeNow);
    }

    private static void removeNow() {
        MAIN.removeCallbacks(SAFETY_HIDE);
        try {
            if (showing && wm != null && view != null) wm.removeView(view);
        } catch (Exception ignored) {}
        showing = false;
        autoSkipMode = false;
        view = null;
        wm = null;
    }

    private static int dp(AccessibilityService c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
