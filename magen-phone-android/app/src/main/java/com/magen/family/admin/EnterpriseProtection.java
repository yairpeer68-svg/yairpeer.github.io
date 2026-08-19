package com.magen.family.admin;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.UserManager;
import android.util.Log;

import com.magen.family.mitm.MitmCaManager;
import com.magen.family.mitm.HttpsInspectionProxy;
import com.magen.family.mitm.MitmPolicy;

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

            // Device Owner/Profile Owner can install the dedicated HTTPS-inspection CA without
            // exporting its private key. The signer private key stays on the VPS.
            MitmCaManager.ensureManagedCaAsync(app);
            configureManagedInspectionProxy(app, MitmPolicy.enabled(app));

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
    /**
     * Device-owner-only recommended global proxy. This broadens coverage on Android versions and
     * libraries that consult the managed proxy, while the Full-Tunnel transparent redirect remains
     * the enforcement path for apps that ignore proxy recommendations. The listener is loopback-only.
     */
    public static boolean configureManagedInspectionProxy(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(app.getPackageName())) return false;
            ComponentName admin = MagenDeviceAdmin.getComponentName(app);
            // v4.5.1 intentionally clears the global explicit proxy. Enforcement is performed by
            // the authenticated transparent Full-Tunnel path; exposing a normal loopback proxy
            // would allow another local app to tunnel through Magen's protected sockets.
            dpm.setRecommendedGlobalProxy(admin, null);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "managed proxy not permitted: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.w(TAG, "managed proxy configuration failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

}
