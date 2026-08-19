package com.magen.family.admin;

import android.app.Activity;
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
 *     • התראה דחופה נרשמת בשרת Magen מיד
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
        EnterpriseProtection.enforce(context);
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
            MagenKillSwitch.start(context, ks);
        } catch (Exception e) {
            Log.e(TAG, "KillSwitch start failed: " + e.getMessage());
        }

        // 2. אזעקה + רישום ל-escalation
        try {
            BehaviorAnalyzer.notifyAlert(context,
                "🚨 ניסיון לבטל את הגנת שומר הברית (מנהל מכשיר)");
        } catch (Exception ignored) {}

        // 3. ההודעה שהמערכת מציגה למשתמש לפני הביטול
        return "⚠️ לביטול הגנת שומר הברית נדרש קוד הורים. " +
               "המכשיר ננעל — לשינוי ההגנה נדרש קוד ההגנה מתוך Magen.";
    }

    /**
     * מופעל אחרי שהאדמין הוסר בפועל. גם אם הצליחו לבטל — נרעיש
     * ונשאיר את שכבת הנעילה פעילה. את ההרשאה מבקשים מחדש רק מתוך Activity
     * גלויה; לא מנסים להקפיץ מסך מערכת מתוך BroadcastReceiver.
     */
    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.w(TAG, "Device Admin DISABLED — re-alerting + re-requesting");

        try {
            NotificationHelper.notifyUrgent(context,
                "🚨 הגנת מנהל המכשיר הוסרה! ייתכן ניסיון להסיר את שומר הברית.");
        } catch (Exception ignored) {}

        // השאר KillSwitch פעיל
        try {
            Intent ks = new Intent(context, MagenKillSwitch.class);
            MagenKillSwitch.start(context, ks);
        } catch (Exception ignored) {}

        // לא פותחים UI מתוך BroadcastReceiver/רקע. באנדרואיד מודרני פתיחת
        // מסך הרשאה מה-background מוגבלת, וב-AOSP מסך ADD_DEVICE_ADMIN גם
        // דוחה הפעלה כ-New Task. ההתראה למעלה מפנה את המשתמש לאפליקציה,
        // ומשם ניתן לבקש מחדש את ההרשאה מתוך Activity חוקית.
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
     * פותח את מסך הפעלת מנהל המכשיר מתוך Activity.
     *
     * ACTION_ADD_DEVICE_ADMIN חייב להישאר באותה task של ה-Activity.
     * אסור להוסיף FLAG_ACTIVITY_NEW_TASK: ב-AOSP מסך DeviceAdminAdd מסיים
     * את עצמו כשמנסים להפעיל ADD_DEVICE_ADMIN כמשימה חדשה.
     */
    public static void requestAdmin(Activity activity, int requestCode) {
        if (activity == null) throw new IllegalArgumentException("activity == null");

        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(activity));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "נדרש כדי להקשות על הסרת שומר הברית ברגע של חולשה. " +
            "אפשר להסיר תמיד עם קוד הברית.");

        // הדרך הראשית: startActivityForResult ללא NEW_TASK.
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (Exception primary) {
            Log.w(TAG, "ADD_DEVICE_ADMIN unavailable, trying settings list: "
                + primary.getClass().getSimpleName());
            try {
                openAdminSettings(activity);
            } catch (Exception fallback) {
                try { fallback.addSuppressed(primary); } catch (Exception ignored) {}
                throw fallback;
            }
        }
    }

    /**
     * fallback ידני אם יצרן מסוים לא מספק את ACTION_ADD_DEVICE_ADMIN.
     * נקרא רק מתוך Activity גלויה.
     */
    public static void openAdminSettings(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("activity == null");
        try {
            activity.startActivity(new Intent("android.settings.DEVICE_ADMIN_SETTINGS"));
        } catch (Exception first) {
            activity.startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
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
