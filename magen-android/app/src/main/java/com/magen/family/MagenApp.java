package com.magen.family;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.magen.family.filter.ContentFilter;
import com.magen.family.filter.DomainVerdict;
import com.magen.family.security.InstallMarker;
import com.magen.family.service.AccountabilityReporter;
import com.magen.family.service.FloatingBadgeService;
import com.magen.family.service.IntegrityGuard;
import com.magen.family.service.MagenKillSwitch;
import com.magen.family.service.NotificationHelper;
import com.magen.family.service.RemoteBlocklist;
import com.magen.family.service.TamperWatcher;
import com.magen.family.service.vpn.VpnPolicy;

import java.util.Calendar;

/**
 * MagenApp — מחלקת Application הראשית.
 *
 * כאן נסגר הפער הגדול ביותר בגרסה הקודמת: שורות האתחול שהתיעוד
 * (INSTALL_FILES.md סעיף 3) הגדיר כנדרשות מעולם לא נוספו. כתוצאה מכך —
 *
 *   • RemoteBlocklist.loadFromCache לא נקרא, ולכן 3 מיליון הדומיינים
 *     שהורדנו כל 24 שעות מעולם לא נטענו ולא סיננו כלום
 *   • AccountabilityReporter.schedule לא נקרא, ולכן הדוח היומי לשותף
 *     האחריות — לב מודל ה"ברית" — מעולם לא רץ
 *   • המדבקה הצפה עלתה רק כשה-Watchdog התעורר, כלומר עד 15 דקות אחרי הפעלה
 *
 * בנוסף נוסף כאן זיהוי "נקה נתונים" — ראה checkClearDataTamper.
 */
public class MagenApp extends Application {

    private static final String TAG = "MagenApp";

    public static final String PREFS_NAME           = "magen_prefs";
    public static final String KEY_PIN              = "parent_pin";
    public static final String KEY_FILTER_ENABLED   = "filter_enabled";
    public static final String KEY_SAFE_SEARCH      = "safe_search";
    public static final String KEY_BLOCK_BROWSERS   = "block_browsers";
    public static final String KEY_BLOCK_SOCIAL     = "block_social";
    public static final String KEY_BLOCK_ADULT      = "block_adult";
    public static final String KEY_SETUP_DONE       = "setup_done";

    public static final String KEY_BLOCKED_COUNT      = "blocked_count";
    public static final String KEY_BLOCKED_TODAY      = "blocked_today";
    public static final String KEY_BLOCKED_WEEK       = "blocked_week";
    public static final String KEY_LAST_BLOCK_TIME    = "last_block_time";
    public static final String KEY_VPN_ATTEMPTS       = "vpn_bypass_attempts";
    public static final String KEY_SETTINGS_ATTEMPTS  = "settings_attempts";
    public static final String KEY_PARENT_PHONE       = "parent_phone";

    private static final String KEY_LAST_DAY_RESET    = "last_day_reset";
    private static final String KEY_LAST_WEEK_RESET   = "last_week_reset";
    private static final String KEY_CLOCK_BASE_SET    = "clock_baseline_set";

    private static MagenApp instance;
    private ContentFilter contentFilter;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        // מחיל את שפת האפליקציה גם על ה-Context הגלובלי (התראות, שירותים)
        super.attachBaseContext(com.magen.family.i18n.LocaleManager.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashLogger.install(this);
        Log.d(TAG, "MagenApp starting...");

        initDefaults();
        migrateLegacyPin();
        resetCountersIfNeeded();

        VpnPolicy.init(this);
        DomainVerdict.init(this);
        contentFilter = new ContentFilter(this);

        // קו בסיס לשעון — פעם אחת בלבד, אחרת כל הפעלה מאפסת את הזיהוי
        if (!getPrefs().getBoolean(KEY_CLOCK_BASE_SET, false)) {
            IntegrityGuard.initClockBaseline(this);
            getPrefs().edit().putBoolean(KEY_CLOCK_BASE_SET, true).apply();
        }

        // דוח יומי לשותף האחריות
        AccountabilityReporter.schedule(this);

        // זיהוי מיידי של כיבוי נגישות / הדלקת ADB
        TamperWatcher.start(this);

        checkClearDataTamper();
        startBadge();

        // טעינת הרשימה המרוחקת ברקע — הקובץ ~3.6MB, קריאה ב-main thread
        // הייתה גורמת ל-ANR בהפעלה
        new Thread(() -> {
            try {
                RemoteBlocklist.loadFromCache(MagenApp.this);
                Log.d(TAG, "blocklist ready: " + RemoteBlocklist.loadedCount() + " domains");
            } catch (Exception e) {
                Log.e(TAG, "blocklist load failed: " + e.getMessage());
            }
        }, "BlocklistLoad").start();

        Log.d(TAG, "MagenApp ready.");
    }

    /**
     * זיהוי "נקה נתונים".
     *
     * הגדרות → אפליקציות → אחסון → נקה נתונים מוחק את כל ה-prefs, כולל ה-hash
     * של ה-PIN, והאפליקציה קמה כאילו היא חדשה — כלומר ההגנה מתאפסת בלי שאיש
     * ידע. (android:allowClearUserData="false" לא עוזר; הוא מכובד רק לאפליקציות
     * מערכת.)
     *
     * הסמן נכתב ל-MediaStore שאינו נמחק עם נתוני האפליקציה, ולכן השילוב
     * "סמן קיים + אין PIN" מזהה בדיוק את המצב הזה.
     */
    private void checkClearDataTamper() {
        try {
            if (hasPin()) return;
            if (!InstallMarker.exists(this)) return;

            Log.w(TAG, "install marker present but PIN missing — app data was cleared");

            NotificationHelper.notifyPartnerUrgent(this,
                "🚨 נתוני שומר הברית נמחקו — ההגנה אופסה וצריך להגדיר קוד מחדש.");

            Intent ks = new Intent(this, MagenKillSwitch.class);
            ks.putExtra("require_pin", true);
            startService(ks);
        } catch (Exception e) {
            Log.e(TAG, "checkClearDataTamper: " + e.getMessage());
        }
    }

    private void startBadge() {
        try {
            Intent badge = new Intent(this, FloatingBadgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(badge);
            else startService(badge);
        } catch (Exception e) {
            Log.w(TAG, "badge start skipped: " + e.getMessage());
        }
    }

    private void initDefaults() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.contains(KEY_PIN)) {
            // PIN ברירת מחדל = ריק. ב-Welcome האפליקציה תכריח את ההורה ליצור.
            prefs.edit()
                .putString(KEY_PIN, "")
                .putBoolean(KEY_FILTER_ENABLED, true)
                .putBoolean(KEY_SAFE_SEARCH, true)
                .putBoolean(KEY_BLOCK_BROWSERS, true)
                .putBoolean(KEY_BLOCK_SOCIAL, false)
                .putBoolean(KEY_BLOCK_ADULT, true)
                .putBoolean(KEY_SETUP_DONE, false)
                .putInt(KEY_BLOCKED_COUNT, 0)
                .apply();
        }
    }

    /**
     * אם יש PIN מהגרסה הישנה ("1234" טקסט / SHA-256 בלי salt),
     * נשמיט אותו ונכריח את ההורה להגדיר מחדש.
     */
    private void migrateLegacyPin() {
        SharedPreferences p = getPrefs();
        String saved = p.getString(KEY_PIN, "");
        if (saved.isEmpty()) return;
        // הפורמט החדש מכיל ":" באמצע (salt:hash)
        if (!saved.contains(":")) {
            Log.w(TAG, "Legacy PIN format detected — clearing, parent must reset");
            p.edit()
                .putString(KEY_PIN, "")
                .putBoolean(KEY_SETUP_DONE, false)
                .apply();
        }
    }

    /** אפס מונים יומיים/שבועיים אם עבר יום/שבוע מאז האיפוס האחרון. */
    private void resetCountersIfNeeded() {
        SharedPreferences p = getPrefs();
        long now = System.currentTimeMillis();
        long lastDayReset  = p.getLong(KEY_LAST_DAY_RESET,  0);
        long lastWeekReset = p.getLong(KEY_LAST_WEEK_RESET, 0);

        if (!isSameDay(lastDayReset, now)) {
            p.edit()
                .putInt(KEY_BLOCKED_TODAY, 0)
                .putLong(KEY_LAST_DAY_RESET, now)
                .apply();
        }
        if (now - lastWeekReset > 7L * 24 * 60 * 60 * 1000) {
            p.edit()
                .putInt(KEY_BLOCKED_WEEK, 0)
                .putLong(KEY_LAST_WEEK_RESET, now)
                .apply();
        }
    }

    private boolean isSameDay(long a, long b) {
        if (a == 0) return false;
        Calendar ca = Calendar.getInstance(); ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance(); cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
            && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    public static MagenApp getInstance() { return instance; }
    public ContentFilter getContentFilter() { return contentFilter; }
    public SharedPreferences getPrefs() { return getSharedPreferences(PREFS_NAME, MODE_PRIVATE); }

    public boolean isFilterEnabled() { return getPrefs().getBoolean(KEY_FILTER_ENABLED, true); }
    public String  getPin()           { return getPrefs().getString(KEY_PIN, ""); }
    public boolean hasPin()           { return !getPin().isEmpty(); }

    public void incrementBlockedCount() {
        resetCountersIfNeeded();
        SharedPreferences.Editor ed = getPrefs().edit();
        ed.putInt(KEY_BLOCKED_COUNT, getPrefs().getInt(KEY_BLOCKED_COUNT, 0) + 1);
        ed.putInt(KEY_BLOCKED_TODAY, getPrefs().getInt(KEY_BLOCKED_TODAY, 0) + 1);
        ed.putInt(KEY_BLOCKED_WEEK,  getPrefs().getInt(KEY_BLOCKED_WEEK, 0) + 1);
        ed.putLong(KEY_LAST_BLOCK_TIME, System.currentTimeMillis());
        ed.apply();
    }

    public void incrementVpnAttempts() {
        int c = getPrefs().getInt(KEY_VPN_ATTEMPTS, 0);
        getPrefs().edit().putInt(KEY_VPN_ATTEMPTS, c + 1).apply();
    }

    public void incrementSettingsAttempts() {
        int c = getPrefs().getInt(KEY_SETTINGS_ATTEMPTS, 0);
        getPrefs().edit().putInt(KEY_SETTINGS_ATTEMPTS, c + 1).apply();
    }
}
