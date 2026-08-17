package com.magen.family.service;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import com.magen.family.MagenApp;

/**
 * Watchdog קשוח — בודק כל 15 דקות שהכל פעיל.
 * עמיד בפני אופטימיזציית סוללה של שיאומי/סמסונג.
 *
 * נוסף בגרסה זו:
 *   • החייאה מרכזית (ServiceRevival) של רכיבי ההגנה.
 *   • בדיקות אבטחה קשות (ADB/root/חתימה/שעון).
 *   • עדכון יומי של רשימת הדומיינים החסומים (UT1 + StevenBlack) —
 *     עם throttle ל-24 שעות ורק כשיש רשת, כדי לא לבזבז נתונים/סוללה.
 *   • ודא שהמדבקה הצפה (FloatingBadgeService) רצה.
 */
public class MagenWatchdogJob extends JobService {

    private static final String TAG = "MagenWatchdog";
    private static final int JOB_ID = 1337;

    private static final String K_LAST_LIST_UPDATE = "last_blocklist_update";
    private static final long LIST_UPDATE_INTERVAL = 24 * 60 * 60 * 1000L;   // 24 שעות

    public static void schedule(Context context) {
        JobScheduler scheduler =
            (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
            new ComponentName(context, MagenWatchdogJob.class))
            .setPeriodic(15 * 60 * 1000L)  // כל 15 דקות
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)  // עובד גם בלי רשת
            .setPersisted(true);           // שרוד reboot

        scheduler.schedule(builder.build());
        Log.d(TAG, "✓ Watchdog scheduled (every 15 min)");
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "🔍 Watchdog check...");

        // === החייאה מרכזית של כל רכיבי ההגנה ===
        try { ServiceRevival.reviveAll(getApplicationContext()); }
        catch (Exception e) { Log.e(TAG, "reviveAll: " + e.getMessage()); }

        // ודא שהזיהוי המיידי (ContentObserver) רשום — הוא נופל יחד עם התהליך
        try { TamperWatcher.start(getApplicationContext()); }
        catch (Exception e) { Log.e(TAG, "tamperWatcher: " + e.getMessage()); }

        // התראה לשותף אם הגנה קריטית כבויה (נגישות/מנהל/overlay/Safe Mode)
        try { ProtectionWatch.checkAsync(getApplicationContext()); }
        catch (Exception e) { Log.e(TAG, "protectionWatch: " + e.getMessage()); }

        // בדוק VPN (גיבוי ל-ServiceRevival)
        if (!MagenVpnService.isVpnRunning) {
            Log.w(TAG, "⚠️ VPN down — restarting");
            Intent vpnIntent = new Intent(this, MagenVpnService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(vpnIntent);
            else
                startService(vpnIntent);
        }

        // בדוק FilterService
        Intent filter = new Intent(this, FilterService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(filter);
        else
            startService(filter);

        // וודא שכל השירותים התומכים רצים
        safeStart(NightModeService.class);
        safeStart(ScreenTimeService.class);
        safeStart(AppScheduleService.class);
        safeStart(GeofenceService.class);
        safeStart(TamperDetectorService.class);

        // ודא שהמדבקה הצפה פעילה
        try {
            Intent badge = new Intent(this, FloatingBadgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(badge);
            else
                startService(badge);
        } catch (Exception e) { Log.e(TAG, "badge: " + e.getMessage()); }

        // === בדיקות אבטחה קשות ===
        try {
            if (SecurityGuard.runSecurityChecks(getApplicationContext()))
                AccountabilityReporter.recordSecurityAlert(getApplicationContext());
            if (IntegrityGuard.runIntegrityChecks(getApplicationContext()))
                AccountabilityReporter.recordSecurityAlert(getApplicationContext());
        } catch (Exception e) { Log.e(TAG, "security checks: " + e.getMessage()); }

        // === זיהוי שיתוף אינטרנט (אם המדיניות דלוקה) ===
        try { HotspotGuard.check(getApplicationContext()); }
        catch (Exception e) { Log.e(TAG, "hotspot check: " + e.getMessage()); }

        // === בדיקת עדכון (אם הוגדר URL, פעם ביום) ===
        try { UpdateChecker.checkIfDue(getApplicationContext()); }
        catch (Exception e) { Log.e(TAG, "update check: " + e.getMessage()); }

        // === עדכון יומי של רשימת הדומיינים (ברקע, רק אם יש רשת + עברו 24ש') ===
        maybeUpdateBlocklist();

        // שטוף digest של אזהרות שמחכות
        try { BehaviorAnalyzer.flushPendingDigest(this); }
        catch (Exception e) { Log.e(TAG, "flushDigest: " + e.getMessage()); }

        Log.d(TAG, "✓ Watchdog check complete");
        jobFinished(params, false);
        return true;
    }

    /**
     * מעדכן את רשימת הדומיינים אם עברו 24 שעות ויש רשת.
     * רץ ב-thread רקע כדי לא לחסום את ה-Job (ההורדה כמה MB).
     */
    private void maybeUpdateBlocklist() {
        try {
            long last = MagenApp.getInstance().getPrefs().getLong(K_LAST_LIST_UPDATE, 0);
            long now = System.currentTimeMillis();
            if (now - last < LIST_UPDATE_INTERVAL) return;   // עוד לא הגיע הזמן
            if (!isNetworkAvailable()) {
                Log.d(TAG, "No network — skipping blocklist update");
                return;
            }

            new Thread(() -> {
                try {
                    Log.d(TAG, "⬇️ Updating blocklist (UT1 + StevenBlack)...");
                    int count = RemoteBlocklist.update(getApplicationContext());
                    if (count > 0) {
                        MagenApp.getInstance().getPrefs().edit()
                            .putLong(K_LAST_LIST_UPDATE, System.currentTimeMillis())
                            .apply();
                        // בלי זה החלטות ישנות היו נשארות ב-cache ורשימה
                        // מעודכנת לא הייתה משפיעה עד להפעלה מחדש
                        com.magen.family.filter.DomainVerdict.clearCache();
                        Log.d(TAG, "✓ Blocklist updated: ~" + count + " domains");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "blocklist update failed: " + e.getMessage());
                }
            }, "BlocklistUpdate").start();

        } catch (Exception e) {
            Log.e(TAG, "maybeUpdateBlocklist: " + e.getMessage());
        }
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return nc != null &&
                    nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                return cm.getActiveNetworkInfo() != null &&
                    cm.getActiveNetworkInfo().isConnected();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void safeStart(Class<?> cls) {
        try { startService(new Intent(this, cls)); }
        catch (Exception e) { Log.e(TAG, "start " + cls.getSimpleName() + ": " + e.getMessage()); }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // retry if stopped
    }
}
