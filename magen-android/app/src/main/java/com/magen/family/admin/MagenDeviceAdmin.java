package com.magen.family.admin;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.magen.family.service.BehaviorAnalyzer;
import com.magen.family.service.MagenKillSwitch;
import com.magen.family.service.NotificationHelper;

/**
 * Device Admin Receiver — הגנה מפני הסרה (בשכבת Device Admin, בלי איפוס).
 *
 * ⚠️ הבהרה חשובה על הגבולות:
 *   ב-Device Admin (בניגוד ל-Device Owner) *אי אפשר* למנוע לגמרי את הביטול.
 *   onDisableRequested יכול רק להציג אזהרה; המערכת עדיין תיתן למשתמש לבטל.
 *   לכן האסטרטגיה כאן היא "לעשות את זה יקר ורועש":
 *     • ברגע שמבקשים לבטל -> KillSwitch נועל את המסך ודורש PIN
 *     • התראה דחופה נשלחת לשותף/הורה מיד
 *     • הניסיון נרשם ב-BehaviorAnalyzer (escalation ל-strict mode)
 *   נעילה אמיתית ובלתי-ניתנת-לביטול קיימת רק ב-Device Owner (מסלול האיפוס).
 */
public class MagenDeviceAdmin extends DeviceAdminReceiver {

    private static final String TAG = "MagenDeviceAdmin";

    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context.getApplicationContext(), MagenDeviceAdmin.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.d(TAG, "Device Admin ENABLED ✓");
        Toast.makeText(context, "🛡️ שומר הברית פעיל", Toast.LENGTH_SHORT).show();
    }

    /**
     * מופעל כשמנסים לבטל את האדמין = ניסיון הסרה.
     * כאן אנחנו: (1) נועלים מיד עם KillSwitch שדורש PIN,
     *           (2) שולחים אזעקה, (3) רושמים את הניסיון.
     */
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        Log.w(TAG, "🚨 Disable requested — locking + alerting");

        // 1. נעילת מסך הנעילה האמיתית של המערכת.
        //    זה חזק בהרבה מה-overlay שהיה כאן קודם: overlay נעקף בלחיצה על
        //    "פתיחה עם קוד" ואז BACK, ואילו מסך הנעילה של המערכת דורש את קוד
        //    המכשיר ואי אפשר לצאת ממנו ב-BACK או ב-HOME.
        lockDeviceNow(context);

        // 2. ה-KillSwitch כשכבה נוספת — מוצג מיד כשהמכשיר נפתח
        try {
            Intent ks = new Intent(context, MagenKillSwitch.class);
            ks.putExtra("require_pin", true);
            context.startService(ks);
        } catch (Exception e) {
            Log.e(TAG, "KillSwitch start failed: " + e.getMessage());
        }

        // 2. אזעקה + רישום ל-escalation
        try {
            BehaviorAnalyzer.notifyPartner(context,
                "🚨 ניסיון לבטל את הגנת שומר הברית (מנהל מכשיר)");
        } catch (Exception ignored) {}

        // 3. ההודעה שהמערכת מציגה למשתמש לפני הביטול
        return "⚠️ לביטול הגנת שומר הברית נדרש קוד הורים. " +
               "המכשיר ננעל — פנה להורה/שותף האחריות.";
    }

    /**
     * מופעל אחרי שהאדמין הוסר בפועל. גם אם הצליחו לבטל — נרעיש,
     * ננסה לבקש את האדמין בחזרה, ונשאיר את הנעילה פעילה.
     */
    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.w(TAG, "Device Admin DISABLED — re-alerting + re-requesting");

        try {
            NotificationHelper.notifyPartnerUrgent(context,
                "🚨 הגנת מנהל המכשיר הוסרה! ייתכן ניסיון להסיר את שומר הברית.");
        } catch (Exception ignored) {}

        // השאר KillSwitch פעיל
        try {
            Intent ks = new Intent(context, MagenKillSwitch.class);
            ks.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startService(ks);
        } catch (Exception ignored) {}

        // בקש את הרשאת האדמין מחדש (המשתמש יראה שוב את המסך)
        try { requestAdmin(context); } catch (Exception ignored) {}
    }

    /**
     * נועל את המסך דרך מדיניות force-lock של Device Admin.
     *
     * ההרשאה הזו כבר הוכרזה ב-device_admin.xml מההתחלה אבל *מעולם לא נקראה*.
     * זו הפעולה החזקה ביותר שיש ל-Device Admin רגיל — היא מפעילה את מסך
     * הנעילה האמיתי של אנדרואיד, שדורש את קוד המכשיר ולא ניתן לעקיפה
     * ב-BACK/HOME כמו overlay.
     *
     * דורש שההורה הגדיר נעילת מסך במכשיר. אם אין נעילה — אין מה לאכוף,
     * ולכן נופלים חזרה ל-KillSwitch.
     */
    public static boolean lockDeviceNow(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            if (!dpm.isAdminActive(getComponentName(context))) {
                Log.w(TAG, "lockNow skipped — admin not active");
                return false;
            }
            dpm.lockNow();
            Log.d(TAG, "device locked via lockNow()");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "lockNow denied: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "lockNow failed: " + e.getMessage());
            return false;
        }
    }

    public static boolean isAdminActive(Context context) {
        DevicePolicyManager dpm =
            (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(getComponentName(context));
    }

    /**
     * פותח את מסך הפעלת מנהל המכשיר.
     *
     * למה כל ההגנות: במסך הזה נצפתה קריסה בהתקנה. לא בכל ROM קיים
     * ACTION_ADD_DEVICE_ADMIN כ-activity שניתן לפתיחה (יצרנים מסתירים או
     * מחליפים אותו), ואז startActivity זורק ActivityNotFoundException.
     * לכן בודקים שהיעד נפתר, ואם לא — נופלים למסך האבטחה של המערכת שממנו
     * אפשר להגיע ל"אפליקציות ניהול מכשיר" ידנית.
     */
    public static void requestAdmin(Context context) {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "נדרש כדי להקשות על הסרת שומר הברית ברגע של חולשה. " +
            "אפשר להסיר תמיד עם קוד הברית.");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            if (context.getPackageManager().resolveActivity(intent, 0) != null) {
                context.startActivity(intent);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "add-admin screen failed: " + e.getMessage());
        }

        // גיבוי — מסך האבטחה של המערכת
        try {
            Intent security = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
            security.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(security);
        } catch (Exception e) {
            Log.e(TAG, "security settings fallback failed: " + e.getMessage());
        }
    }

    /** הסרה — נקראת רק אחרי אימות PIN מצד ההורה (מסך ההגדרות). */
    public static void removeAdmin(Context context) {
        DevicePolicyManager dpm =
            (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            dpm.removeActiveAdmin(getComponentName(context));
        }
    }
}
