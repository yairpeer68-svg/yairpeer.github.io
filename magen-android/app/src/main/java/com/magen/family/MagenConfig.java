package com.magen.family;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/**
 * הגדרות מרכזיות — Whitelist + אפליקציות חסומות על ידי המשתמש
 */
public class MagenConfig {

    private static final String PREFS_NAME = "MagenConfigPrefs";
    private static final String KEY_BLOCKED_APPS = "BlockedApps";
    private static final String KEY_LOCKOUT_ENABLED = "LockoutEnabled";
    private static final String KEY_LOCKOUT_MINUTES = "LockoutMinutes";

    // ===== Whitelist קבועה — לעולם לא לחסום =====
    private static final Set<String> WHITELIST = new HashSet<>();
    static {
        WHITELIST.add("com.magen.family");                          // האפליקציה שלנו
        WHITELIST.add("com.magen.family.debug");                    // גרסת debug
        WHITELIST.add("com.google.android.apps.bard");             // Gemini
        WHITELIST.add("com.google.android.inputmethod.latin");     // מקלדת Google
        WHITELIST.add("com.samsung.android.honeyboard");           // מקלדת Samsung
        WHITELIST.add("com.swiftkey.swiftkeyapp");                 // SwiftKey
        WHITELIST.add("com.google.android.dialer");                // טלפון
        WHITELIST.add("com.android.phone");                        // שיחות
        WHITELIST.add("com.android.emergency");                    // חירום
        WHITELIST.add("com.google.android.apps.maps");             // Google Maps
        WHITELIST.add("com.waze");                                 // Waze
        WHITELIST.add("com.anthropic.claude");                     // Claude
    }

    public static boolean isWhitelisted(String packageName) {
        return WHITELIST.contains(packageName);
    }

    // ===== אפליקציות שהמשתמש (ההורה) בחר לחסום =====
    public static void setAppBlocked(Context context, String packageName, boolean isBlocked) {
        if (isWhitelisted(packageName)) return; // לא חוסמים whitelist

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> blockedApps = new HashSet<>(
            prefs.getStringSet(KEY_BLOCKED_APPS, new HashSet<>())
        );

        if (isBlocked) {
            blockedApps.add(packageName);
        } else {
            blockedApps.remove(packageName);
        }
        prefs.edit().putStringSet(KEY_BLOCKED_APPS, blockedApps).apply();
    }

    public static boolean isAppBlockedByUser(Context context, String packageName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> blockedApps = prefs.getStringSet(KEY_BLOCKED_APPS, new HashSet<>());
        return blockedApps.contains(packageName);
    }

    public static Set<String> getBlockedApps(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_BLOCKED_APPS, new HashSet<>()));
    }
    // ===== נעילת צינון בעת זיהוי תוכן =====
    public static boolean isLockoutEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCKOUT_ENABLED, false);
    }

    public static void setLockoutEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCKOUT_ENABLED, enabled).apply();
    }

    public static int getLockoutMinutes(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LOCKOUT_MINUTES, 10);
    }

    public static void setLockoutMinutes(Context context, int minutes) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LOCKOUT_MINUTES, minutes).apply();
    }

}
