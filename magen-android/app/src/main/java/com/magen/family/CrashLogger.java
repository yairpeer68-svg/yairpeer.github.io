package com.magen.family;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CrashLogger — לוכד Exception לא-מטופלים וכותב אותם לקובץ.
 * שימוש: CrashLogger.install(this) ב-MagenApp.onCreate.
 * הקובץ נשמר ב-Download/magen_crashes/crash.log לקריאה ידנית.
 */
public class CrashLogger {
    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler previous =
            Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.US).format(new Date());

                // getExternalFilesDir ולא DIRECTORY_DOWNLOADS:
                // תחת Scoped Storage (אנדרואיד 10+) כתיבה לתיקייה ציבורית בלי
                // WRITE_EXTERNAL_STORAGE נכשלת בשקט, ולכן לוגי הקריסה מעולם לא
                // נכתבו בפועל. התיקייה הפרטית תמיד זמינה ולא דורשת הרשאה.
                File dir = new File(ctx.getExternalFilesDir(null), "crashes");
                if (!dir.exists() && !dir.mkdirs()) return;
                File log = new File(dir, "crash.log");

                // מניעת גדילה בלי גבול
                if (log.exists() && log.length() > 256 * 1024) {
                    //noinspection ResultOfMethodCallIgnored
                    log.delete();
                }

                FileWriter fw = new FileWriter(log, true);
                fw.write("\n===== " + time + " =====\n");
                fw.write("Thread: " + thread.getName() + "\n");
                fw.write(sw.toString());
                fw.write("\n");
                fw.close();
            } catch (Exception ignored) {}

            if (previous != null) previous.uncaughtException(thread, ex);
        });
    }
}
