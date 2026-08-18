package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;

import com.magen.family.MagenApp;

/**
 * MagenGuard — חלונות הרשאה קצרים וממוקדים.
 *
 * במקום "חלון תחזוקה" גלובלי שמאפשר לעבור במשך כמה דקות לכל מסך רגיש,
 * כל פתיחה מורשית מקבלת scope יחיד: נגישות, מנהל מכשיר, VPN, overlay וכו'.
 * כך PIN שאושר לצורך פעולה אחת לא הופך בטעות למפתח לכל הגדרות המערכת.
 */
public final class MagenGuard {

    public static final String SCOPE_NONE          = "";
    public static final String SCOPE_ACCESSIBILITY = "accessibility";
    public static final String SCOPE_DEVICE_ADMIN  = "device_admin";
    public static final String SCOPE_VPN           = "vpn";
    public static final String SCOPE_OVERLAY       = "overlay";
    public static final String SCOPE_BATTERY       = "battery";
    public static final String SCOPE_APP_DETAILS   = "app_details";
    public static final String SCOPE_USAGE         = "usage";
    public static final String SCOPE_LOCATION      = "location";
    public static final String SCOPE_AUTOSTART     = "autostart";

    private static final String KEY_UNTIL = "maintenance_until";
    private static final String KEY_SCOPE = "maintenance_scope";
    private static final String KEY_SETUP_UNTIL = "setup_grace_until";
    private static final String KEY_SETUP_SCOPE = "setup_grace_scope";

    // זמן קצר מספיק כדי להגיע למסך היעד ולשנות את ההרשאה, בלי להשאיר דלת פתוחה.
    private static final long WINDOW_MS = 90 * 1000L;
    // במסך onboarding המשתמש עלול להתעכב באישור ביומטרי/אזהרת OEM.
    private static final long SETUP_GRACE_MS = 3 * 60 * 1000L;

    private MagenGuard() {}

    public static void grantSetupGrace(Context ctx, String scope) {
        if (scope == null || scope.isEmpty()) return;
        prefs(ctx).edit()
            .putLong(KEY_SETUP_UNTIL, System.currentTimeMillis() + SETUP_GRACE_MS)
            .putString(KEY_SETUP_SCOPE, scope)
            .apply();
    }

    public static void endSetupGrace(Context ctx) {
        prefs(ctx).edit().remove(KEY_SETUP_UNTIL).remove(KEY_SETUP_SCOPE).apply();
    }

    public static void grantMaintenance(Context ctx, String scope) {
        if (scope == null || scope.isEmpty()) return;
        prefs(ctx).edit()
            .putLong(KEY_UNTIL, System.currentTimeMillis() + WINDOW_MS)
            .putString(KEY_SCOPE, scope)
            .apply();
    }

    public static void endMaintenance(Context ctx) {
        prefs(ctx).edit().remove(KEY_UNTIL).remove(KEY_SCOPE).apply();
    }

    /** האם קיים כרגע חלון מורשה כלשהו. */
    public static boolean inMaintenance(Context ctx) {
        return activeScope(ctx) != null;
    }

    /** האם המסך המסוים הזה הוא זה שאושר. */
    public static boolean allows(Context ctx, String requestedScope) {
        if (requestedScope == null || requestedScope.isEmpty()) return false;
        String active = activeScope(ctx);
        return requestedScope.equals(active);
    }

    /** scope פעיל, או null. מצב חירום נשמר כ-"*" ומאפשר recovery מכוון. */
    public static String activeScope(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            long now = System.currentTimeMillis();

            if (p.getBoolean("emergency_mode", false)
                    && now < p.getLong("emergency_mode_until", 0)) {
                return "*";
            }

            long regularUntil = p.getLong(KEY_UNTIL, 0);
            if (now < regularUntil) {
                String s = p.getString(KEY_SCOPE, "");
                if (s != null && !s.isEmpty()) return s;
            } else if (regularUntil != 0) {
                p.edit().remove(KEY_UNTIL).remove(KEY_SCOPE).apply();
            }

            long setupUntil = p.getLong(KEY_SETUP_UNTIL, 0);
            if (now < setupUntil) {
                String s = p.getString(KEY_SETUP_SCOPE, "");
                if (s != null && !s.isEmpty()) return s;
            } else if (setupUntil != 0) {
                p.edit().remove(KEY_SETUP_UNTIL).remove(KEY_SETUP_SCOPE).apply();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** מצב חירום הוא החריג היחיד שמאפשר recovery מכל מסך רגיש. */
    public static boolean allowsAnySensitiveScreen(Context ctx) {
        return "*".equals(activeScope(ctx));
    }

    /** ההגנה חמושה מהרגע שקיים קוד ברית. */
    public static boolean isArmed(Context ctx) {
        try {
            String pin = ctx.getSharedPreferences(MagenApp.PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(MagenApp.KEY_PIN, "");
            return pin != null && !pin.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(MagenApp.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
