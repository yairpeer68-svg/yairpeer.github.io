package com.magen.family.service;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import com.magen.family.MagenApp;

/**
 * Watchdog תקופתי — מבצע audit של שכבות ההגנה ועדכונים בטוחים.
 *
 * חשוב: JobScheduler רץ ברקע, ולכן ה-Job לא מנסה להרים בכוח Foreground
 * Services. הוא בודק מצב, מתריע על VPN שנפל, מריץ בדיקות integrity, בודק
 * עדכון חתום ומרענן blocklist כאשר יש רשת. כל העבודה האסינכרונית נשארת
 * קשורה לחיי ה-Job ו-jobFinished נקרא פעם אחת בסיום.
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

    private volatile Thread worker;
    private volatile JobParameters activeParams;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "🔍 Watchdog check...");

        activeParams = params;
        worker = new Thread(() -> {
            try {
                Context app = getApplicationContext();

                // JobScheduler runs in the background. Android 12+ does not generally
                // allow a background job to resurrect foreground services, so this job
                // audits and alerts instead of relying on an illegal FGS start. Sticky
                // services, boot handling and user-visible repair flows own service starts.
                try { TamperWatcher.start(app); }
                catch (Exception e) { Log.e(TAG, "tamperWatcher: " + e.getMessage()); }

                try { ProtectionWatch.checkAsync(app); }
                catch (Exception e) { Log.e(TAG, "protectionWatch: " + e.getMessage()); }

                // במכשיר מנוהל, מאשר מחדש את מדיניות ה-VPN ברמת ה-OS.
                try { com.magen.family.admin.EnterpriseProtection.enforce(app); }
                catch (Exception e) { Log.w(TAG, "enterprise enforcement: " + e.getMessage()); }

                // Sync signed VPS policy and send health without making the server a
                // dependency of local enforcement. Failures are intentionally non-fatal.
                if (com.magen.family.server.ServerConfig.ready(app)) {
                    try { com.magen.family.server.PolicySyncManager.syncBlocking(app); }
                    catch (Exception e) { Log.w(TAG, "policy sync: " + e.getMessage()); }
                    try { com.magen.family.server.HeartbeatManager.sendBlocking(app); }
                    catch (Exception e) { Log.w(TAG, "heartbeat: " + e.getMessage()); }
                }

                if (!MagenVpnService.isVpnRunning) {
                    Log.w(TAG, "VPN down — background restart is not permitted on modern Android");
                    if (SecurityGuard.shouldAlert("last_vpn_down_alert_at")) {
                        NotificationHelper.notifyUrgent(app,
                            "⚠️ שירות ה-VPN אינו פעיל. פתח את שומר הברית כדי לחדש את ההגנה.");
                        com.magen.family.server.ServerEventReporter.report(app, "VPN_DOWN", "HIGH", "watchdog detected VPN inactive");
                        SecurityGuard.markAlerted("last_vpn_down_alert_at");
                    }
                }

                try {
                    if (SecurityGuard.runSecurityChecks(app))
                        ActivityReporter.recordSecurityAlert(app);
                    if (IntegrityGuard.runIntegrityChecks(app))
                        ActivityReporter.recordSecurityAlert(app);
                } catch (Exception e) { Log.e(TAG, "security checks: " + e.getMessage()); }

                try { HotspotGuard.check(app); }
                catch (Exception e) { Log.e(TAG, "hotspot check: " + e.getMessage()); }

                try { UpdateChecker.checkIfDueBlocking(app); }
                catch (Exception e) { Log.e(TAG, "update check: " + e.getMessage()); }

                maybeUpdateBlocklist();

                try { BehaviorAnalyzer.flushPendingDigest(this); }
                catch (Exception e) { Log.e(TAG, "flushDigest: " + e.getMessage()); }

                Log.d(TAG, "✓ Watchdog check complete");
            } finally {
                if (activeParams == params) {
                    activeParams = null;
                    worker = null;
                    jobFinished(params, false);
                }
            }
        }, "MagenWatchdogWorker");
        worker.start();
        return true;
    }

    /**
     * מעדכן את רשימת הדומיינים אם עברו 24 שעות ויש רשת.
     * נקרא מתוך worker של ה-Job, כך שהעבודה נשארת קשורה למחזור החיים שלו.
     */
    private void maybeUpdateBlocklist() {
        try {
            long last = MagenApp.getInstance().getPrefs().getLong(K_LAST_LIST_UPDATE, 0);
            long now = System.currentTimeMillis();
            if (now - last < LIST_UPDATE_INTERVAL) return;
            if (!isNetworkAvailable()) {
                Log.d(TAG, "No network — skipping blocklist update");
                return;
            }

            Log.d(TAG, "⬇️ Updating blocklist (UT1 + StevenBlack)...");
            int count = RemoteBlocklist.update(getApplicationContext());
            // RemoteBlocklist returns -1 on any partial/failed update and preserves
            // the last-known-good filter, so failures are retried on the next job.
            if (count > 0) {
                MagenApp.getInstance().getPrefs().edit()
                    .putLong(K_LAST_LIST_UPDATE, System.currentTimeMillis())
                    .apply();
                com.magen.family.filter.DomainVerdict.clearCache();
                Log.d(TAG, "✓ Blocklist ready: ~" + count + " domains");
            }
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

    @Override
    public boolean onStopJob(JobParameters params) {
        if (activeParams == params) {
            activeParams = null;
            Thread t = worker;
            worker = null;
            if (t != null) t.interrupt();
        }
        return true; // retry if the OS stopped the audit before completion
    }
}
