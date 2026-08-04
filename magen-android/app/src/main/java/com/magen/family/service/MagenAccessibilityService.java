package com.magen.family.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.magen.family.MagenApp;
import com.magen.family.MagenConfig;
import com.magen.family.R;
import com.magen.family.filter.AhoCorasick;
import com.magen.family.filter.ContentFilter;
import com.magen.family.ui.BlockedActivity;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MagenAccessibilityService extends AccessibilityService {

    private static final String TAG = "MagenAccessibility";

    private static final long DOM_SCAN_INTERVAL_MS = 300;
    private static final long SOCIAL_SCAN_INTERVAL_MS = 150;
    private long lastDomScanAt = 0;

    /** עומק מקסימלי לסריקת עץ הצמתים — מגן מפני עצים עמוקים מאוד. */
    private static final int MAX_SCAN_DEPTH = 12;

    // צינון חסימת תוכן — מונע לולאה אינסופית של חסימה→בית→חסימה
    private static final long CONTENT_BLOCK_COOLDOWN_MS = 6000;
    private String lastContentBlockPkg = "";
    private long lastContentBlockTime = 0;

    private static final long URL_BLOCK_DEDUPE_MS = 2000;
    private String lastBlockedUrl = "";
    private long  lastBlockTime = 0;

    private ContentFilter contentFilter;
    private BehaviorAnalyzer behaviorAnalyzer;
    private final AhoCorasick matcher = new AhoCorasick();
    private final android.os.Handler mainHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());

    private static final Set<String> BLOCKED_APPS_FIXED = new HashSet<>(Arrays.asList(
        "org.torproject.android", "org.torproject.torbrowser",
        "com.expressvpn.vpn", "com.nordvpn.android",
        "com.tunnelbear.android", "com.privateinternetaccess.android",
        "com.protonvpn.android", "de.blinkt.openvpn",
        "com.vyprvpn.android", "com.ipvanish.android",
        "com.windscribe.vpn", "com.surfshark.vpnclient.android",
        "com.cyberghostvpn.android", "com.purevpn.purevpnandroid",
        "com.hotspotshield.android.vpn", "com.anchorfree.vpn",
        "com.ultrasurf.android", "org.zwanoo.android.speedify",
        "com.psiphon3", "com.psiphon3.subscription",
        "com.x8.android",
        "free.vpn.proxy.secure",
        "com.fast.free.unblock.thunder.vpn",
        "com.signallab.thunder",
        "com.pandavpn.androidproxy",
        "com.vpn.free.hotspot.secure.vpnify",
        "com.atlasvpn.free",
        "com.onlyfans", "tv.fmovies.app",
        "org.briarproject.briar.android",
        // אפליקציות VPN/DNS שהיו חסרות — כל אחת מבטלת את ה-VPN שלנו
        "com.cloudflare.onedotonedotonedotone",   // 1.1.1.1 / WARP
        "com.getsurfboard", "com.wireguard.android",
        "org.mullvad.mullvadvpn", "com.privatevpn.android",
        "hotspotshield.android.vpn", "com.free.vpn.super.hotspot.open",
        "com.bitdefender.vpn", "com.avira.vpn", "com.avast.android.vpn",
        "com.hidemyass.hidemyassprovpn", "io.github.getsops",
        "org.outline.android.client", "net.openvpn.openvpn",
        "com.v2ray.ang", "com.github.shadowsocks", "com.v2ray.actinium",
        "app.openconnect", "de.blinkt.openvpn.api"
    ));

    private static final Set<String> VPN_PACKAGES_FOR_DETECTION = new HashSet<>(BLOCKED_APPS_FIXED);

    private static final Set<String> SOCIAL_SCAN_PACKAGES = new HashSet<>(Arrays.asList(
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.instagram.android",
        "com.facebook.android",
        "com.snapchat.android",
        "com.twitter.android",
        "com.reddit.frontpage",
        // אפליקציות חיפוש — הבעיה החמורה: חיפוש תמונות באפליקציית Google לא
        // עבר דרך שום דפדפן, ולכן לא נסרק. עכשיו נסרק כמו אפליקציה חברתית
        // (טקסט מוקלד + DOM), כך שהשאילתה "porn" והתוצאות נחסמות.
        "com.google.android.googlequicksearchbox",   // אפליקציית Google
        "com.google.android.apps.searchlite",         // Google Go
        "com.sec.android.app.sbrowser",               // Samsung Internet (גם דפדפן)
        "com.pinterest",
        "com.google.android.apps.photos"              // חיפוש בגלריה
    ));

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        contentFilter = new ContentFilter(this);
        behaviorAnalyzer = new BehaviorAnalyzer(this);
        behaviorAnalyzer.setListener((pattern, details) -> {
            Log.w("Behavior", "⚠️ " + pattern + ": " + details);
        });

        try {
            String[] words = getResources().getStringArray(R.array.banned_words_array);
            matcher.addAll(Arrays.asList(words));
            List<String> custom = UpdateService.getCustomBlacklist();
            if (!custom.isEmpty()) matcher.addAll(custom);
            matcher.build();
            Log.d(TAG, "✓ Loaded blacklist");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load banned words: " + e.getMessage());
        }

        new UpdateService(this).checkAll();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                         AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                     AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout = 100L;
        setServiceInfo(info);

        Log.d(TAG, "✓ Connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!((MagenApp) getApplication()).isFilterEnabled()) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        String className = event.getClassName() != null ? event.getClassName().toString() : "";

        // אל תסרוק UI מערכת/שולחן עבודה/Termux
        if (pkg.equals("com.termux") ||
            pkg.equals("com.android.systemui") ||
            pkg.endsWith(".launcher") ||
            pkg.contains(".launcher.") ||
            pkg.equals("com.coloros.launcher") ||
            pkg.equals("com.realme.launcher") ||
            pkg.equals("com.heytap.launcher") ||
            pkg.equals("com.miui.home") ||
            pkg.equals("com.google.android.apps.nexuslauncher") ||
            pkg.equals("android")) {
            return;
        }

        // בדיקת VPN בכל אירוע — לא לחכות ל-state change
        if (BLOCKED_APPS_FIXED.contains(pkg)) {
            if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
            block();
            return;
        }
        // דיאלוג הסכמת VPN — ABORT מיידי
        if (pkg.equals("com.android.vpndialogs") || pkg.contains("vpndialog")) {
            if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }

        if (MagenConfig.isWhitelisted(pkg)) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (behaviorAnalyzer != null) {
                behaviorAnalyzer.recordAppSwitch("", pkg);
            }

            if (handleSelfDefense(pkg)) return;

            if (BehaviorAnalyzer.isStrictMode()
                && !pkg.equals(getPackageName())
                && !pkg.equals("com.android.phone")
                && !pkg.equals("com.android.systemui")
                && !MagenConfig.isWhitelisted(pkg)) {
                block();
                return;
            }

            if (MagenConfig.isAppBlockedByUser(this, pkg)) {
                block();
                return;
            }

            if (AppScheduleService.isAppBlockedNow(this, pkg)) {
                block();
                return;
            }
        }

        boolean isBrowser  = isBrowser(pkg);
        boolean isSocial   = SOCIAL_SCAN_PACKAGES.contains(pkg);
        boolean isWebView  = className.contains("WebView") || className.contains("CustomTab");
        boolean isUserBlk  = MagenConfig.isAppBlockedByUser(this, pkg);
        if (!isBrowser && !isSocial && !isWebView && !isUserBlk) return;

        long now = System.currentTimeMillis();
        // ה-throttle חל על *כל* האפליקציות, כולל החברתיות.
        // קודם היה כאן "isSocial ||", כלומר בטיקטוק/יוטיוב/אינסטגרם סריקת ה-DOM
        // הרקורסיבית רצה על כל אירוע תוכן בלי שום השהיה — מקור ודאי ל-ANR
        // בדיוק באפליקציות שמייצרות הכי הרבה אירועים.
        // לאפליקציות חברתיות ניתן מרווח קצר יותר, אבל לא אפס.
        long interval = isSocial ? SOCIAL_SCAN_INTERVAL_MS : DOM_SCAN_INTERVAL_MS;
        // מצב חיסכון סוללה — מרחיבים את מרווח הסריקה כדי לחסוך אנרגיה.
        // הסינון עדיין פועל, פשוט סורק בתדירות נמוכה יותר.
        if (isPowerSave(now)) interval *= 3;
        boolean shouldScanDom = (now - lastDomScanAt) >= interval;

        // רמת סינון LIGHT מסתמכת על דומיינים בלבד — בלי סריקת מילים
        boolean useKeywords = com.magen.family.filter.FilterPolicy.useKeywords(this);

        // טקסט שהוקלד — בדיקה מהירה, רק לא בדפדפן (כדי להימנע ממילים בהיסטוריה)
        if (useKeywords && !isBrowser && event.getText() != null && !event.getText().isEmpty()) {
            CharSequence t = event.getText().get(0);
            if (t != null && matcher.contains(t.toString())) {
                block();
                return;
            }
        }

        // סריקת DOM — רק אם לא דפדפן (בדפדפן מסתפקים ב-URL)
        if (useKeywords && !isBrowser && shouldScanDom && (
            event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) {
            lastDomScanAt = now;

            // צינון — אל תחסום שוב את אותה אפליקציה תוך 6 שניות (מונע לולאה אינסופית)
            if (pkg.equals(lastContentBlockPkg) && (now - lastContentBlockTime) < CONTENT_BLOCK_COOLDOWN_MS) {
                return;
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    if (scanNodeRecursive(root)) {
                        lastContentBlockPkg = pkg;
                        lastContentBlockTime = now;
                        block();
                        return;
                    }
                } finally {
                    root.recycle();
                }
            }
        }

        // URL filter בדפדפן
        if (isBrowser) {
            String url = extractUrl();
            if (url != null && !url.isEmpty()) {
                // שומר חיפוש — שאילתה עם מילה אסורה
                String query = useKeywords ? extractSearchQuery(url) : null;
                if (query != null && matcher.contains(query)) {
                    confirmAndBlockSearch(query);
                    return;
                }
                if (!url.equals(lastBlockedUrl) || (now - lastBlockTime) > URL_BLOCK_DEDUPE_MS) {
                    if (contentFilter.shouldBlock(url)) {
                        lastBlockedUrl = url;
                        lastBlockTime = now;
                        blockBrowser(url);
                    }
                }
            }
        }
    }

    /**
     * מחלץ את טקסט השאילתה מ-URL של מנוע חיפוש (Google/Bing/DDG/YouTube),
     * או null אם זו אינה שאילתת חיפוש.
     */
    private String extractSearchQuery(String url) {
        try {
            String low = url.toLowerCase();
            boolean isSearch = low.contains("/search?") || low.contains("q=") ||
                               low.contains("search_query=") || low.contains("/results?") ||
                               low.contains("tbm=isch") || low.contains("&q=");
            if (!isSearch) return null;

            String query = "";
            for (String param : new String[]{"q=", "search_query=", "p=", "query="}) {
                int idx = low.indexOf(param);
                if (idx >= 0) {
                    int start = idx + param.length();
                    int end = low.indexOf('&', start);
                    if (end < 0) end = low.length();
                    query = url.substring(start, Math.min(end, url.length()));
                    break;
                }
            }
            query = query.replace('+', ' ').replace("%20", " ");
            try { query = java.net.URLDecoder.decode(query, "UTF-8"); } catch (Exception ignored) {}
            return query.isEmpty() ? null : query;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * חוסם שאילתת חיפוש — אבל קודם, אם DeepSeek מופעל ולא במצב מחמיר, שואל
     * אותו בהקשר כדי לא לחסום שאילתות לגיטימיות ("בריאות מינית", "סרטן השד").
     *
     * הבדיקה רצה ב-thread רקע (יש בה קריאת רשת — אסור על ה-thread של הנגישות),
     * וברירת המחדל הבטוחה: אם המודל לא זמין / כבוי / STRICT — חוסמים.
     */
    private void confirmAndBlockSearch(String query) {
        boolean strict = com.magen.family.filter.FilterPolicy.aggressive(this);
        boolean deepseek = com.magen.family.filter.DeepSeekClassifier.isEnabled(this);

        if (strict || !deepseek) {
            blockSearchNow();
            return;
        }

        new Thread(() -> {
            Boolean verdict = com.magen.family.filter.DeepSeekClassifier
                .classifyBlocking(getApplicationContext(), query);
            // BLOCK או null (לא זמין) → חוסמים. ALLOW → משחררים.
            if (verdict == null || verdict) {
                mainHandler.post(this::blockSearchNow);
            }
        }, "SearchConfirm").start();
    }

    private void blockSearchNow() {
        performGlobalAction(GLOBAL_ACTION_BACK);
        block();
    }

    private boolean handleSelfDefense(String pkg) {
        if (isSettingsPackage(pkg)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    // מסך הנגישות של האפליקציה שלנו — אם השירות רץ (כלומר מגיע אירוע),
                    // כל ביקור כאן הוא ניסיון השבתה → חסום
                    boolean ourAccessibility =
                        (findText(root, "שומר הברית") || findText(root, "Magen")) &&
                        (findText(root, "השתמש בשירות") || findText(root, "Use service") ||
                         findText(root, "סינון תוכן"));
                    if (ourAccessibility) {
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
                        block();
                        return true;
                    }

                    boolean dangerous = findText(root, "הסר התקנה") ||
                                       findText(root, "אלץ עצירה") ||
                                       findText(root, "עצור בכוח") ||
                                       findText(root, "השבת") ||
                                       findText(root, "כבה") ||
                                       findText(root, "Uninstall") ||
                                       findText(root, "Force stop") ||
                                       findText(root, "Disable") ||
                                       findText(root, "נקה נתונים") ||
                                       findText(root, "Clear data") ||
                                       findText(root, "Clear storage");
                    boolean ourApp = findText(root, "שומר הברית") ||
                                    findText(root, "Magen") ||
                                    findText(root, "magen.family");
                    if (dangerous && ourApp) {
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
                        block();
                        return true;
                    }

                    // מסך הרשאות / מידע-על-האפליקציה של האפליקציה שלנו: כאן אפשר לשלול
                    // הרשאות קריטיות (נגישות, אחסון, מיקום) ובכך לשתק את הסינון *בלי*
                    // להסיר את האפליקציה — ולכן חייבים לחסום אותו גם כן.
                    //   • רק כשההגדרה הראשונית הושלמה (onboarding_done), אחרת נחסום את
                    //     מתן ההרשאות הלגיטימי בהתקנה הראשונה.
                    //   • רק כשגם שם האפליקציה שלנו מופיע במסך — כדי לא לחסום מסכי
                    //     הרשאות/מידע של אפליקציות אחרות.
                    boolean setupDone = false;
                    try {
                        setupDone = com.magen.family.MagenApp.getInstance()
                            .getPrefs().getBoolean("onboarding_done", false);
                    } catch (Exception ignored) {}
                    if (setupDone && ourApp) {
                        boolean permissionScreen =
                            findText(root, "הרשאות") ||
                            findText(root, "Permissions") ||
                            findText(root, "מידע על האפליקציה") ||
                            findText(root, "App info") ||
                            findText(root, "App details") ||
                            findText(root, "פרטי אפליקציה") ||
                            findText(root, "אחסון ומטמון") ||
                            findText(root, "Storage & cache") ||
                            findText(root, "אחסון ונתונים");
                        if (permissionScreen) {
                            if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
                            block();
                            return true;
                        }
                    }
                    // רק מסך הוספה/עריכה של VPN, לא כל מסך שמופיעה בו המילה.
                    // קודם התנאי היה findText("VPN") לבדו, ולכן ההורה נזרק
                    // מ"רשת ואינטרנט" ומכל תוצאת חיפוש בהגדרות שוב ושוב.
                    boolean vpnConfigScreen =
                        (findText(root, "VPN") || findText(root, "רשת פרטית"))
                        && (findText(root, "הוסף") || findText(root, "Add VPN")
                            || findText(root, "פרופיל") || findText(root, "profile")
                            || findText(root, "always-on") || findText(root, "תמיד פעיל"));
                    if (vpnConfigScreen) {
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
                        block();
                        return true;
                    }
                    // מסך "הצג מעל אפליקציות אחרות" — חסום רק אם ה-KillSwitch פעיל
                    // (כדי לא לחסום בהתקנה הראשונית כשמאשרים את ההרשאה)
                    if (MagenKillSwitch.isActive() && (
                        findText(root, "הצג מעל אפליקציות אחרות") ||
                        findText(root, "Display over other apps") ||
                        findText(root, "Appear on top") ||
                        findText(root, "הופעה מעל") ||
                        findText(root, "מוצג מעל"))) {
                        block();
                        return true;
                    }
                    if (findText(root, "ניהול מכשיר") || findText(root, "Device admin") ||
                        findText(root, "אפליקציות ניהול") || findText(root, "מנהלי מכשיר")) {
                        if (findText(root, "שומר הברית") || findText(root, "Magen")) {
                            if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
                            block();
                            return true;
                        }
                    }

                    // אפשרויות למפתחים — וקטור עקיפה חזק: "שירותים פעילים" מאפשר
                    // עצירת השירות שלנו, אפשר לכבות always-on VPN, ולהדליק ניפוי-USB
                    // (adb) שמאפשר הסרה. מזהים לפי סמנים שמופיעים *בתוך* הדף עצמו
                    // (לא הכותרת ברשימת "מערכת") כדי לא ללכוד את המשתמש מחוץ לכל
                    // ההגדרות. פעיל רק אחרי סיום ההגדרה הראשונית.
                    if (setupDone && (
                            findText(root, "שירותים פעילים") ||
                            findText(root, "Running services") ||
                            findText(root, "ניפוי באגים ב-USB") ||
                            findText(root, "USB debugging") ||
                            findText(root, "ביטול נעילה של OEM") ||
                            findText(root, "OEM unlocking"))) {
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
                        block();
                        return true;
                    }

                    // ביטול פטור מאופטימיזציית סוללה לאפליקציה שלנו → המערכת (Doze)
                    // עלולה להרוג את שירות הסינון ברקע. חוסמים את המסך הזה שלנו.
                    if (setupDone && ourApp && (
                            findText(root, "אופטימיזציית סוללה") ||
                            findText(root, "אופטימיזציה של סוללה") ||
                            findText(root, "Battery optimization") ||
                            findText(root, "Battery optimisation"))) {
                        block();
                        return true;
                    }
                } finally {
                    root.recycle();
                }
            }
            return true;
        }

        // VPN dialog — דחה אוטומטית
        if (pkg.equals("com.android.vpndialogs") || pkg.contains("vpndialog")) {
            if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME);
            block();
            return true;
        }

        if (pkg.contains("packageinstaller")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    if (findText(root, "שומר הברית") || findText(root, "magen.family")) {
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
                        block();
                        return true;
                    }
                } finally {
                    root.recycle();
                }
            }
            return true;
        }

        return false;
    }

    private boolean scanNodeRecursive(AccessibilityNodeInfo node) {
        return scanNodeRecursive(node, 0);
    }

    private boolean scanNodeRecursive(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_SCAN_DEPTH) return false;

        CharSequence text = node.getText();
        if (!TextUtils.isEmpty(text) && matcher.contains(text.toString())) return true;

        CharSequence desc = node.getContentDescription();
        if (!TextUtils.isEmpty(desc) && matcher.contains(desc.toString())) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            boolean hit = scanNodeRecursive(child, depth + 1);
            if (child != null) child.recycle();
            if (hit) return true;
        }
        return false;
    }

    // מצב חיסכון סוללה — נבדק לכל היותר פעם ב-30 שנ' (isPowerSaveMode הוא IPC)
    private long lastPowerCheck = 0;
    private boolean powerSaveCached = false;

    private boolean isPowerSave(long now) {
        if (now - lastPowerCheck > 30_000L) {
            lastPowerCheck = now;
            try {
                android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
                powerSaveCached = pm != null && pm.isPowerSaveMode();
            } catch (Exception e) {
                powerSaveCached = false;
            }
        }
        return powerSaveCached;
    }

    private void block() {
        if (behaviorAnalyzer != null) {
            behaviorAnalyzer.checkNightUsage();
            behaviorAnalyzer.checkBlockingSpike();
        }
        MagenTransparentBlock.show(this);
        performGlobalAction(GLOBAL_ACTION_HOME);
        performGlobalAction(GLOBAL_ACTION_BACK);

        // נעילת צינון — אם מופעלת, נעל את הטלפון לזמן שנבחר
        if (MagenConfig.isLockoutEnabled(this)) {
            int minutes = MagenConfig.getLockoutMinutes(this);
            Intent ks = new Intent(this, MagenKillSwitch.class);
            ks.putExtra("lockout_minutes", minutes);
            try { startService(ks); } catch (Exception ignored) {}
        }
    }

    private void blockBrowser(String url) {
        performGlobalAction(GLOBAL_ACTION_BACK);
        Intent i = new Intent(this, BlockedActivity.class);
        i.putExtra("blocked_url", url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    private boolean findText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        List<AccessibilityNodeInfo> found = node.findAccessibilityNodeInfosByText(text);
        boolean has = found != null && !found.isEmpty();
        if (found != null) {
            for (AccessibilityNodeInfo n : found) if (n != null) n.recycle();
        }
        return has;
    }

    private boolean isBrowser(String pkg) {
        return ContentFilter.BROWSER_PACKAGES.contains(pkg) ||
               pkg.contains("chrome") || pkg.contains("browser") ||
               pkg.contains("firefox") || pkg.contains("opera");
    }

    private boolean isSettingsPackage(String pkg) {
        return pkg.equals("com.android.settings") ||
               pkg.equals("com.coloros.settings") ||
               pkg.equals("com.oplus.settings") ||
               pkg.equals("com.miui.securitycenter") ||
               pkg.equals("com.samsung.android.lool") ||
               pkg.startsWith("com.android.settings") ||
               // מסך ההרשאות באנדרואיד 10+ רץ בחבילה נפרדת — בלי זה ההגנה
               // העצמית לא רצה כלל על מסך ההרשאות, ואפשר היה לשנות הרשאות
               // בלי להיזרק. זו הייתה פרצה אמיתית.
               pkg.contains("permissioncontroller") ||
               pkg.equals("com.android.packageinstaller") ||
               pkg.equals("com.google.android.packageinstaller") ||
               pkg.contains("com.samsung.android.permission");
    }

    private String extractUrl() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            String url = getViewText(root, "com.android.chrome:id/url_bar");
            if (url == null) url = getViewText(root, "com.microsoft.emmx:id/url_bar");
            if (url == null) url = getViewText(root, "com.brave.browser:id/url_bar");
            if (url == null) url = getViewText(root, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view");
            if (url == null) url = getViewText(root, "com.sec.android.app.sbrowser:id/location_bar_edit_text");
            return url;
        } finally {
            root.recycle();
        }
    }

    private String getViewText(AccessibilityNodeInfo root, String viewId) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                CharSequence t = nodes.get(0).getText();
                for (AccessibilityNodeInfo n : nodes) if (n != null) n.recycle();
                if (t != null) return t.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        // כיבוי שירות הנגישות = ליבת הסינון מתה. TamperWatcher כבר מזהה את זה
        // מיידית דרך ContentObserver ונועל, אבל מנסים גם להקים את שאר השכבות.
        try {
            startService(new Intent(this, FilterService.class));
        } catch (Exception ignored) {}
    }
}
