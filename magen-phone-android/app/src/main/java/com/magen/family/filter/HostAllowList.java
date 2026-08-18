package com.magen.family.filter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * HostAllowList — דומיינים שההורה פתח ידנית (אחרי אימות PIN).
 *
 * נחוץ בגלל ה-Bloom filter: הוא מבטיח אפס false-negative אבל יש בו ~1%
 * false-positive, כלומר דומיין תמים עלול להיחסם בטעות. בלי דרך לפתוח דומיין
 * ספציפי, המשתמש תקוע בלי אפשרות תיקון — וזה בדיוק מה שגורם לאנשים לעקוף
 * את המסנן לגמרי.
 *
 * ה-allow list גובר על כל שכבות החסימה.
 */
public final class HostAllowList {

    private static final String PREFS = "magen_allowlist";
    private static final String KEY   = "allowed_hosts";

    private HostAllowList() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** האם המארח או אחד מדומייני-העל שלו ברשימת ההיתר? */
    public static boolean isAllowed(Context ctx, String host) {
        if (host == null || host.isEmpty()) return false;
        Set<String> allowed = get(ctx);
        if (allowed.isEmpty()) return false;

        String h = DomainVerdict.normalize(host);
        if (allowed.contains(h)) return true;

        // sub.example.com מותר אם example.com מותר
        int dot = h.indexOf('.');
        while (dot >= 0 && dot < h.length() - 1) {
            String parent = h.substring(dot + 1);
            if (parent.indexOf('.') < 0) break;      // הגענו ל-TLD
            if (allowed.contains(parent)) return true;
            dot = h.indexOf('.', dot + 1);
        }
        return false;
    }

    public static Set<String> get(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY, Collections.emptySet()));
    }

    public static void allow(Context ctx, String host) {
        String h = DomainVerdict.normalize(host);
        if (h.isEmpty()) return;
        Set<String> set = get(ctx);
        set.add(h);
        prefs(ctx).edit().putStringSet(KEY, set).apply();
        DomainVerdict.clearCache();
    }

    public static void remove(Context ctx, String host) {
        String h = DomainVerdict.normalize(host);
        Set<String> set = get(ctx);
        if (set.remove(h)) {
            prefs(ctx).edit().putStringSet(KEY, set).apply();
            DomainVerdict.clearCache();
        }
    }
}
