package com.magen.family.security;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.OutputStream;

/**
 * InstallMarker — סמן התקנה ששורד "נקה נתונים".
 *
 * הבעיה שזה פותר:
 *   הגדרות -> אפליקציות -> אחסון -> "נקה נתונים" מוחק את כל SharedPreferences,
 *   כולל ה-hash של ה-PIN. האפליקציה קמה במצב "אין PIN מוגדר" — כלומר ההגנה
 *   מתאפסת לגמרי בלי לדעת שקרה משהו.
 *   android:allowClearUserData="false" ב-Manifest *לא עוזר* — הוא מכובד רק
 *   לאפליקציות מערכת, ובאפליקציה רגילה מתעלמים ממנו בשקט.
 *
 * הפתרון:
 *   בהגדרה הראשונה כותבים קובץ סמן קטן דרך MediaStore לתיקיית Documents.
 *   MediaStore הוא אחסון משותף — הוא *לא* נמחק ב"נקה נתונים" ואפילו שורד
 *   הסרת התקנה. באתחול בודקים:
 *
 *     סמן קיים + אין PIN  ->  נוקו נתונים. נועלים ומתריעים.
 *
 * מגבלה ישרה:
 *   מי שיודע מה הוא עושה יכול למחוק את הקובץ ממנהל הקבצים. זו שכבת זיהוי,
 *   לא מניעה — בדיוק כמו שאר ההגנות במסלול Device Admin.
 */
public class InstallMarker {

    private static final String TAG = "InstallMarker";
    private static final String DIR_NAME  = "Magen";
    private static final String FILE_NAME = "magen_install.id";
    private static final String CONTENT   = "magen-family-protection-marker\n";

    /** כותב את הסמן. נקרא פעם אחת אחרי שההורה מגדיר PIN. */
    public static void write(Context ctx) {
        if (exists(ctx)) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(ctx);
            } else {
                writeViaLegacyFile();
            }
            Log.d(TAG, "install marker written");
        } catch (Exception e) {
            // לא קריטי — אם לא הצלחנו לכתוב, פשוט אין שכבת הזיהוי הזו
            Log.w(TAG, "write marker failed: " + e.getMessage());
        }
    }

    /** האם הסמן קיים? */
    public static boolean exists(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return existsViaMediaStore(ctx);
            }
            return legacyFile().exists();
        } catch (Exception e) {
            Log.w(TAG, "exists check failed: " + e.getMessage());
            return false;
        }
    }

    // ---------------- Android 10+ (MediaStore) ----------------

    private static void writeViaMediaStore(Context ctx) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOCUMENTS + File.separator + DIR_NAME);

        Uri uri = ctx.getContentResolver()
            .insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
        if (uri == null) throw new IllegalStateException("insert returned null");

        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("openOutputStream null");
            os.write(CONTENT.getBytes("UTF-8"));
        }
    }

    private static boolean existsViaMediaStore(Context ctx) {
        String[] projection = { MediaStore.MediaColumns._ID };
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] args = { FILE_NAME };

        try (Cursor c = ctx.getContentResolver().query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                projection, selection, args, null)) {
            return c != null && c.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- Android 9 ומטה ----------------

    private static File legacyFile() {
        File dir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            DIR_NAME);
        return new File(dir, FILE_NAME);
    }

    private static void writeViaLegacyFile() throws Exception {
        File f = legacyFile();
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("mkdirs failed");
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
            fos.write(CONTENT.getBytes("UTF-8"));
        }
    }
}
