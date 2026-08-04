package com.magen.family.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BootReceiver — מקים את ההגנה מחדש אחרי:
 *   • אתחול המכשיר (BOOT_COMPLETED / LOCKED_BOOT_COMPLETED)
 *   • עדכון האפליקציה (MY_PACKAGE_REPLACED)
 *   • החזרה מ-Quickboot של יצרנים (HTC/Xiaomi/…)
 *
 * צריך להצהיר עליו ב-AndroidManifest (ראה WIRING.md) עם ה-intent-filters
 * וההרשאה RECEIVE_BOOT_COMPLETED.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onReceive: " + action);

        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_PACKAGE_REPLACED:
                Log.d(TAG, "Reviving protection after: " + action);
                try {
                    ServiceRevival.reviveAll(context.getApplicationContext());
                } catch (Exception e) {
                    Log.e(TAG, "revive failed: " + e.getMessage());
                }
                // בדיקת אבטחה מיידית (ADB/root/חתימה) אחרי אתחול
                try {
                    SecurityGuard.runSecurityChecks(context.getApplicationContext());
                    IntegrityGuard.runIntegrityChecks(context.getApplicationContext());
                } catch (Exception ignored) {}
                // אחרי אתחול (כולל חזרה מ-Safe Mode) — בדיקה אם הגנה קריטית כבויה
                try {
                    ProtectionWatch.checkAsync(context.getApplicationContext());
                } catch (Exception ignored) {}
                break;
            default:
                break;
        }
    }
}
