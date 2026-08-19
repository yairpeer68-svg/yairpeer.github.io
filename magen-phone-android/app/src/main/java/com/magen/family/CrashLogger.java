package com.magen.family;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.magen.family.ui.CrashActivity;
import com.magen.family.server.ServerEventReporter;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CrashLogger — לוכד קריסות, בונה דוח קריא, ומציג אותו למשתמש.
 *
 * למה זה קיים:
 *   כשמשתמש אומר "האפליקציה קורסת" אין דרך לדעת למה בלי logcat. עכשיו
 *   בכל קריסה נפתח מסך (CrashActivity) עם הדוח המלא — סוג התקלה, השורה
 *   בקוד ופרטי המכשיר — עם כפתור העתקה/שיתוף. אבחון מרחוק בלחיצה אחת.
 *
 * שלוש שכבות שמירה, כי בקריסה כל אחת עלולה להיכשל:
 *   1. SharedPreferences (commit, לא apply — התהליך גוסס) — מוצג בפתיחה הבאה
 *   2. קובץ ב-getExternalFilesDir/crashes/crash.log — היסטוריה
 *   3. מסך שקופץ מיד, בתהליך נפרד ששורד את מות התהליך הראשי
 */
public class CrashLogger {

    private static final String PREFS = "magen_crash";
    private static final String KEY_PENDING = "pending_report";

    public static void install(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            String report = buildReport(app, thread, ex);

            // 1. שמירה מתמידה — הבסיס. אם כל השאר ייכשל, נציג בפתיחה הבאה.
            try {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                   .edit().putString(KEY_PENDING, report).commit();
            } catch (Exception ignored) {}

            // 2. קובץ להיסטוריה
            try { writeToFile(app, report); } catch (Exception ignored) {}

            // 2b. תור telemetry מתמיד. אין רשת כאן: התהליך קורס ולכן שומרים
            // סינכרונית ושולחים בהפעלה/heartbeat הבא.
            try { ServerEventReporter.enqueueForLaterSync(app, "APP_CRASH_JAVA", "CRITICAL", buildTelemetry(thread, ex)); }
            catch (Exception ignored) {}

            // 3. מסך שקופץ מיד
            try {
                Intent i = new Intent(app, CrashActivity.class);
                i.putExtra(CrashActivity.EXTRA_REPORT, report);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                         | Intent.FLAG_ACTIVITY_CLEAR_TASK
                         | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                app.startActivity(i);
            } catch (Exception ignored) {}

            if (previous != null) {
                previous.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /** דוח קריסה ממתין שטרם הוצג (או null). קריאה מסירה אותו. */
    public static String consumePending(Context ctx) {
        try {
            android.content.SharedPreferences p =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String r = p.getString(KEY_PENDING, null);
            if (r != null) p.edit().remove(KEY_PENDING).apply();
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * רישום תקלה שנתפסה (לא קריסה) — למשל מסך הרשאה שלא נפתח.
     * נשמר לקובץ בלבד, בלי להפריע למשתמש.
     */
    public static void logHandled(Context ctx, String where, Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            if (t != null) t.printStackTrace(new PrintWriter(sw));
            writeToFile(ctx.getApplicationContext(), "[HANDLED] " + where + "\n" + sw);
            JSONObject d=new JSONObject().put("where",where==null?"":where);
            if(t!=null){
                d.put("exception",t.getClass().getName());
                if(t.getMessage()!=null)d.put("message",redact(t.getMessage(),800));
                StackTraceElement[] f=t.getStackTrace(); if(f!=null&&f.length>0)d.put("top_frame",f[0].toString());
            }
            ServerEventReporter.report(ctx,"APP_HANDLED_ERROR","MEDIUM",d);
        } catch (Exception ignored) {}
    }


    private static JSONObject buildTelemetry(Thread thread, Throwable ex) {
        JSONObject d=new JSONObject();
        try {
            d.put("exception",ex==null?"unknown":ex.getClass().getName());
            d.put("thread",thread==null?"?":thread.getName());
            d.put("sdk_int",Build.VERSION.SDK_INT);
            d.put("device",Build.MANUFACTURER+" "+Build.MODEL);
            d.put("process_uptime_ms",com.magen.family.server.RealtimeHealthReporter.processUptimeMs());
            if(ex!=null){
                if(ex.getMessage()!=null)d.put("message",redact(ex.getMessage(),1000));
                StackTraceElement[] frames=ex.getStackTrace(); StringBuilder st=new StringBuilder();
                int n=Math.min(frames==null?0:frames.length,35);
                for(int i=0;i<n;i++){ if(st.length()>3800)break; st.append(frames[i].toString()).append('\n'); }
                d.put("stack",st.toString());
                if(frames!=null&&frames.length>0)d.put("top_frame",frames[0].toString());
                if(ex.getCause()!=null)d.put("cause",ex.getCause().getClass().getName());
            }
        } catch(Exception ignored) {}
        return d;
    }

    /** Redact common secrets/query strings before crash telemetry leaves the device. */
    private static String redact(String value, int max) {
        if (value == null) return "";
        String s = value;
        try {
            s = s.replaceAll("(?i)(authorization|bearer|api[_-]?key|token|password|secret)\\s*[:=]\\s*[^\\s,;]+", "$1=<redacted>");
            s = s.replaceAll("(?i)(https?://[^\\s?#]+)\\?[^\\s]+", "$1?<redacted>");
        } catch (Exception ignored) {}
        return s.substring(0, Math.min(max, s.length()));
    }

    private static String buildReport(Context ctx, Thread thread, Throwable ex) {
        StringWriter sw = new StringWriter();
        if (ex != null) ex.printStackTrace(new PrintWriter(sw));

        String version = "?";
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) {}

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("שומר הברית — דוח תקלה\n");
        sb.append("=======================\n");
        sb.append("זמן:       ").append(time).append('\n');
        sb.append("גרסה:      ").append(version).append('\n');
        sb.append("מכשיר:     ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("אנדרואיד:  ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Thread:    ").append(thread != null ? thread.getName() : "?").append('\n');
        if (ex != null) {
            sb.append("סוג התקלה: ").append(ex.getClass().getName()).append('\n');
            if (ex.getMessage() != null) {
                sb.append("הודעה:     ").append(ex.getMessage()).append('\n');
            }
        }
        sb.append("\n----- Stack trace -----\n");
        sb.append(sw);
        return sb.toString();
    }

    private static void writeToFile(Context ctx, String report) throws Exception {
        // getExternalFilesDir ולא DIRECTORY_DOWNLOADS: תחת Scoped Storage כתיבה
        // לתיקייה ציבורית בלי הרשאה נכשלת בשקט. התיקייה הפרטית תמיד זמינה.
        File dir = new File(ctx.getExternalFilesDir(null), "crashes");
        if (!dir.exists() && !dir.mkdirs()) return;
        File log = new File(dir, "crash.log");
        if (log.exists() && log.length() > 256 * 1024) {
            //noinspection ResultOfMethodCallIgnored
            log.delete();
        }
        try (FileWriter fw = new FileWriter(log, true)) {
            fw.write("\n===== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + " =====\n");
            fw.write(report);
            fw.write("\n");
        }
    }
}
