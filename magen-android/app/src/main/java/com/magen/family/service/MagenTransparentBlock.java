package com.magen.family.service;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * חסימה שקופה — בולעת לחיצות ומונעת אינטראקציה
 * עדין יותר מ-HOME_ACTION, בדיוק כמו רימון
 */
public class MagenTransparentBlock {

    private static WindowManager windowManager;
    private static FrameLayout blockView;
    private static WindowManager.LayoutParams layoutParams;
    private static boolean isShowing = false;
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    // טיימר בטחון — מסיר לאחר 5 שניות
    private static final Runnable safetyWatchdog = MagenTransparentBlock::hide;

    public static void show(Context context) {
        uiHandler.post(() -> {
            try {
                if (isShowing) {
                    uiHandler.removeCallbacks(safetyWatchdog);
                    uiHandler.postDelayed(safetyWatchdog, 5000);
                    return;
                }

                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

                blockView = new FrameLayout(context);
                blockView.setBackgroundColor(Color.parseColor("#01000000")); // שקוף כמעט

                // בלע לחיצות — Touch Hijacking
                blockView.setOnTouchListener((v, event) -> true);

                int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

                layoutParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }

                windowManager.addView(blockView, layoutParams);
                isShowing = true;

                uiHandler.removeCallbacks(safetyWatchdog);
                uiHandler.postDelayed(safetyWatchdog, 5000);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void hide() {
        uiHandler.post(() -> {
            try {
                uiHandler.removeCallbacks(safetyWatchdog);
                if (windowManager != null && blockView != null && isShowing) {
                    windowManager.removeView(blockView);
                    isShowing = false;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static boolean isActive() {
        return isShowing;
    }
}
