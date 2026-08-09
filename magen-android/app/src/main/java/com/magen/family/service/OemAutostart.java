package com.magen.family.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * OemAutostart — פתיחת מסך "הפעלה אוטומטית" (Autostart) הספציפי ליצרן.
 *
 * למה זה קריטי לאמינות:
 *   שיאומי (MIUI), אופו/וניל (ColorOS), ויוו (Funtouch), וואווי (EMUI) ואחרים
 *   הורגים שירותי רקע של אפליקציות צד-שלישי *גם* כשיש פטור מאופטימיזציית
 *   סוללה — אלא אם המשתמש הדליק במפורש "הפעלה אוטומטית" לאפליקציה. זה
 *   הגורם מס' 1 לכך שאפליקציות כאלה "מפסיקות לעבוד" פתאום על המכשירים האלה.
 *
 *   אין דרך תקנית לבדוק אם ההרשאה ניתנה (אין API), ולכן מפנים את המשתמש
 *   למסך הנכון ומסבירים. אם אין מסך יצרן — נופלים למסך פרטי-האפליקציה.
 */
public final class OemAutostart {

    /** רשימת מסכי ה-Autostart הידועים, לפי יצרן. */
    private static final ComponentName[] CANDIDATES = new ComponentName[] {
        // Xiaomi / Redmi / POCO (MIUI / HyperOS)
        new ComponentName("com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // MIUI — "הרשאות אחרות": כולל "הצג חלונות קופצים ברקע", שבלעדיה
        // מסך החסימה שלנו לא יכול לעלות כשהאפליקציה ברקע.
        new ComponentName("com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity"),
        // OnePlus (OxygenOS) — ניהול הפעלה אוטומטית
        new ComponentName("com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        // Oppo / Realme (ColorOS)
        new ComponentName("com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        new ComponentName("com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        new ComponentName("com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"),
        // Vivo / iQOO (Funtouch)
        new ComponentName("com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        new ComponentName("com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // Huawei / Honor (EMUI)
        new ComponentName("com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        new ComponentName("com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        // Letv
        new ComponentName("com.letv.android.letvsafe",
            "com.letv.android.letvsafe.AutobootManageActivity"),
        // Asus
        new ComponentName("com.asus.mobilemanager",
            "com.asus.mobilemanager.entry.FunctionActivity"),
    };

    private static final String[] AUTOSTART_MANUFACTURERS = {
        "xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo",
        "huawei", "honor", "letv", "asus", "oneplus", "meizu"
    };

    private OemAutostart() {}

    /** האם היצרן הזה ידוע כמי שהורג שירותים בלי הרשאת Autostart? */
    public static boolean isLikelyNeeded() {
        String m = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase());
        String b = (Build.BRAND == null ? "" : Build.BRAND.toLowerCase());
        for (String s : AUTOSTART_MANUFACTURERS) {
            if (m.contains(s) || b.contains(s)) return true;
        }
        return false;
    }

    /**
     * פותח את מסך ה-Autostart של היצרן אם קיים; אחרת מסך פרטי-האפליקציה.
     * מחזיר true אם נפתח מסך כלשהו.
     *
     * מנסים לפתוח ישירות ולא בודקים resolveActivity מראש: מאנדרואיד 11 חל
     * סינון נראות חבילות, ולכן resolveActivity על חבילות של יצרנים
     * (com.miui.securitycenter וכו') מחזיר null גם כשהמסך קיים — בדיקה
     * מקדימה הייתה מונעת את פתיחתו לחלוטין.
     */
    public static boolean open(Context ctx) {
        for (ComponentName cn : CANDIDATES) {
            try {
                Intent i = new Intent().setComponent(cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Exception ignored) {
                // המסך הזה לא קיים ביצרן הזה — ננסה את הבא
            }
        }
        // גיבוי — מסך פרטי האפליקציה
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + ctx.getPackageName()));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(details);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
