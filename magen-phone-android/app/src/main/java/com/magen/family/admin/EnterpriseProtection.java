package com.magen.family.admin;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.UserManager;
import android.util.Log;

/**
 * שכבת האכיפה החזקה ביותר שאנדרואיד נותן לאפליקציית ניהול.
 *
 * במצב Device Owner / Profile Owner המערכת עצמה אוכפת Magen כ-VPN תמידי
 * עם lockdown, מונעת יצירת VPN חלופי ומונעת הסרה. בהתקנה רגילה של Device
 * Admin המתודות האלו אינן מורשות ולכן אנחנו פשוט מחזירים false ונשענים על
 * Accessibility + TamperWatcher + זיהוי VPN דינמי.
 */
public final class EnterpriseProtection {
    private static final String TAG = "MagenEnterprise";
    private EnterpriseProtection() {}

    public static boolean isManagedOwner(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            String pkg = context.getPackageName();
            return dpm.isDeviceOwnerApp(pkg) || dpm.isProfileOwnerApp(pkg);
        } catch (Exception e) {
            return false;
        }
    }

    /** מנסה להפעיל אכיפת OS. בטוח לקריאה חוזרת (idempotent best-effort). */
    public static boolean enforce(Context context) {
        Context app = context.getApplicationContext();
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            String pkg = app.getPackageName();
            if (!dpm.isDeviceOwnerApp(pkg) && !dpm.isProfileOwnerApp(pkg)) return false;

            ComponentName admin = MagenDeviceAdmin.getComponentName(app);

            // Android framework routes all traffic through Magen and blocks leaks if it drops.
            dpm.setAlwaysOnVpnPackage(admin, pkg, true);

            // No user-created competing VPNs / ADB bypass while managed.
            try { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN); }
            catch (Exception e) { Log.w(TAG, "DISALLOW_CONFIG_VPN: " + e.getMessage()); }
            try { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS); }
            catch (Exception e) { Log.w(TAG, "DISALLOW_CONFIG_PRIVATE_DNS: " + e.getMessage()); }
            try { dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL); }
            catch (Exception e) { Log.w(TAG, "DISALLOW_APPS_CONTROL: " + e.getMessage()); }
            try { dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT); }
            catch (Exception e) { Log.w(TAG, "DISALLOW_SAFE_BOOT: " + e.getMessage()); }
            try { dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES); }
            catch (Exception e) { Log.w(TAG, "DISALLOW_DEBUGGING_FEATURES: " + e.getMessage()); }

            // Prevent enabling third-party accessibility services that could automate bypasses.
            // Magen itself remains permitted. System accessibility services remain available.
            try { dpm.setPermittedAccessibilityServices(admin, java.util.Collections.singletonList(pkg)); }
            catch (Exception e) { Log.w(TAG, "setPermittedAccessibilityServices: " + e.getMessage()); }

            // Device owner/profile owner can prevent removal of the enforcement app itself.
            try { dpm.setUninstallBlocked(admin, pkg, true); }
            catch (Exception e) { Log.w(TAG, "setUninstallBlocked: " + e.getMessage()); }

            Log.i(TAG, "managed enforcement active: always-on VPN + lockdown + app/VPN restrictions");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "owner enforcement not permitted: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "owner enforcement failed: " + e.getMessage());
            return false;
        }
    }
}
