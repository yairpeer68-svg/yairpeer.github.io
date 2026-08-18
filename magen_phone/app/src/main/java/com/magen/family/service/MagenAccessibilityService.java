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
import com.magen.family.server.RemoteIntelligenceClient;
import com.magen.family.server.ServerEventReporter;
import com.magen.family.ui.BlockedActivity;
import com.magen.family.visual.MagenVisualCurtain;
import com.magen.family.visual.NsfwResult;
import com.magen.family.visual.VisualShieldEngine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MagenAccessibilityService extends AccessibilityService {

    private static final String TAG = "MagenAccessibility";

    private static final long DOM_SCAN_INTERVAL_MS = 300;
    /** אפליקציות שאינן דפדפן/חברתית — סריקה נדירה יותר, כדי לשמור על סוללה. */
    private static final long GENERIC_SCAN_INTERVAL_MS = 1500;
    private static final long SOCIAL_SCAN_INTERVAL_MS = 150;
    /** Telegram: שולחים טקסט גלוי ל-VPS לכל היותר פעם ב-350ms ורק כשיש שינוי. */
    private static final long TELEGRAM_AI_INTERVAL_MS = 350;
    private long lastTelegramAiAt = 0;
    private int lastTelegramTextHash = 0;
    private long lastDomScanAt = 0;

    /** מיתון בדיקת ההגנה העצמית על אירועי תוכן (findText יקר). */
    private static final long SELF_DEFENSE_INTERVAL_MS = 700;
    private long lastSelfDefenseAt = 0;

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
    private VisualShieldEngine visualShield;
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
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        // אפליקציות חיפוש — הבעיה החמורה: חיפוש תמונות באפליקציית Google לא
        // עבר דרך שום דפדפן, ולכן לא נסרק. עכשיו נסרק כמו אפליקציה חברתית
        // (טקסט מוקלד + DOM), כך שהשאילתה "porn" והתוצאות נחסמות.
        "com.google.android.googlequicksearchbox",   // אפליקציית Google
        "com.google.android.apps.searchlite",         // Google Go
        "com.sec.android.app.sbrowser",               // Samsung Internet (גם דפדפן)
        "com.pinterest",
        "com.google.android.apps.photos"              // חיפוש בגלריה
    ));

    private static final Set<String> TELEGRAM_PACKAGES = new HashSet<>(Arrays.asList(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram"
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

        // Preserve capabilities declared in accessibility_service_config.xml (especially
        // canTakeScreenshot). Replacing the object with a brand-new AccessibilityServiceInfo
        // can silently lose static metadata on some Android builds.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                         AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED |
                         AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                      AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                      AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout = 100L;
        setServiceInfo(info);

        visualShield = new VisualShieldEngine(this, (blockedPkg, result) ->
            mainHandler.post(() -> blockVisual(blockedPkg, result)));

        Log.d(TAG, "✓ Connected + Visual Shield");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        String className = event.getClassName() != null ? event.getClassName().toString() : "";

        // ההגנה מפני שיבוש רצה *לפני* ובלי תלות במתג סינון התוכן.
        // קודם היה כאן "אם הסינון כבוי — צא", ולכן כיבוי המתג הרג גם את
        // ההגנה העצמית, ואפשר היה לשנות כל הרשאה בחופשיות.
        //
        // בנוסף: הבדיקה רצה גם על WINDOW_CONTENT_CHANGED (ממותנן), לא רק על
        // מעבר בין מסכים. בלי זה, מסך הגדרות שכבר פתוח אפשר היה לשנות בו
        // מתגים בלי ששום בדיקה תרוץ — פרצה אמיתית.
        if (isSettingsPackage(pkg) || pkg.contains("vpndialog")) {
            long nowSd = System.currentTimeMillis();
            boolean stateChanged =
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            if (stateChanged || nowSd - lastSelfDefenseAt >= SELF_DEFENSE_INTERVAL_MS) {
                lastSelfDefenseAt = nowSd;
                if (stateChanged) {   // רק במעבר מסך, כדי לא להציף את היומן
                    com.magen.family.debug.DebugLog.log(this, "הגנה",
                        "מסך הגדרות: " + pkg
                            + " | חמושה=" + (MagenGuard.isArmed(this) ? "כן" : "לא")
                            + " | תחזוקה=" + (MagenGuard.inMaintenance(this) ? "כן" : "לא"));
                }
                if (handleSelfDefense(pkg, className)) return;
            }
            // אם זה מסך מערכת שאושר ב-scope המדויק, אין שום סיבה להעביר את
            // הטקסט שלו למנוע סינון התוכן. אחרת מילה תמימה במסך הגדרות יכולה
            // להפעיל חסימת תוכן בזמן שההורה רק מאשר הרשאה.
            return;
        }

        if (!((MagenApp) getApplication()).isFilterEnabled()) return;

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
        if (!pkg.equals(getPackageName()) &&
            (BLOCKED_APPS_FIXED.contains(pkg) || AppInstallReceiver.isVpnCapable(this, pkg))) {
            if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
            ServerEventReporter.report(this, "VPN_APP_BLOCKED", "HIGH", "package=" + pkg);
            block();
            return;
        }
        // דיאלוג הסכמת VPN. בזמן onboarding/תחזוקה זהו dialog שאנחנו
        // פתחנו בכוונה, ולכן אסור להגנה העצמית לסגור אותו. מחוץ לחלון
        // התחזוקה הוא עדיין נחשב ניסיון לעקוף את ה-VPN שלנו.
        if (pkg.equals("com.android.vpndialogs") || pkg.contains("vpndialog")) {
            if (MagenGuard.allowsAnySensitiveScreen(this) || MagenGuard.allows(this, MagenGuard.SCOPE_VPN)) return;
            if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }

        if (MagenConfig.isWhitelisted(pkg)) return;

        // Visual filtering is independent from the query/URL: it classifies what is actually
        // rendered on screen. It is local-only; the VPS receives only decision metadata.
        if (visualShield != null && !MagenVisualCurtain.isShowing()) {
            visualShield.maybeScan(pkg, event.getEventType());
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (behaviorAnalyzer != null) {
                behaviorAnalyzer.recordAppSwitch("", pkg);
            }

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
        boolean isKnown    = isBrowser || isSocial || isWebView || isUserBlk;

        // סריקת טקסט אוניברסלית: קודם נסרקו רק דפדפנים ורשימה קבועה של ~14
        // אפליקציות, וכל אפליקציה אחרת לא נסרקה כלל — כלומר *כן* היה משנה
        // איפה הטקסט נכתב. עכשיו נסרקת כל אפליקציה, כדי שמילה אסורה תיתפס
        // בכל מקום. אפליקציות לא-מוכרות מקבלות מרווח סריקה ארוך יותר כדי
        // לא לשלם על כך בסוללה ובביצועים.
        //
        // אין צורך בצילומי מסך: שירות הנגישות קורא את הטקסט עצמו — זול,
        // מדויק, ובלי הרשאת הקלטת מסך.
        if (pkg.equals(getPackageName())) return;   // האפליקציה שלנו

        long now = System.currentTimeMillis();
        // ה-throttle חל על *כל* האפליקציות, כולל החברתיות.
        // קודם היה כאן "isSocial ||", כלומר בטיקטוק/יוטיוב/אינסטגרם סריקת ה-DOM
        // הרקורסיבית רצה על כל אירוע תוכן בלי שום השהיה — מקור ודאי ל-ANR
        // בדיוק באפליקציות שמייצרות הכי הרבה אירועים.
        // לאפליקציות חברתיות ניתן מרווח קצר יותר, אבל לא אפס.
        long interval = isSocial ? SOCIAL_SCAN_INTERVAL_MS
                      : (isKnown ? DOM_SCAN_INTERVAL_MS : GENERIC_SCAN_INTERVAL_MS);
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

        // סריקת DOM — רק אם לא דפדפן (בדפדפן מסתפקים ב-URL).
        // Telegram נשלח ל-VPS גם ברמת סינון LIGHT: ה-AI הוא שכבת בטיחות נפרדת
        // מהמילון המקומי ולא אמור להיעלם רק מפני שהמשתמש הוריד את רמת הסינון.
        if (!isBrowser && shouldScanDom && (
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
                    // Telegram: כל שינוי טקסט/תיאור גלוי נשלח ל-VPS/DeepSeek במקביל.
                    // החסימה המקומית עדיין קודמת בזמן כשהמילון מופעל, ולכן מילה
                    // מפורשת מוכרת נחסמת בלי להמתין לרשת.
                    if (TELEGRAM_PACKAGES.contains(pkg)) {
                        maybeClassifyTelegramVisibleText(root, pkg, now);
                    }
                    if (useKeywords && scanNodeRecursive(root)) {
                        lastContentBlockPkg = pkg;
                        lastContentBlockTime = now;
                        ServerEventReporter.report(this, "CONTENT_BLOCK_LOCAL", "HIGH", "package=" + pkg);
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

    private boolean handleSelfDefense(String pkg, String className) {
        boolean armed = MagenGuard.isArmed(this);

        // מצב חירום הוא recovery מכוון. PIN רגיל אינו נותן יותר "גישה לכל ההגדרות".
        if (MagenGuard.allowsAnySensitiveScreen(this)) return false;

        // זיהוי לפי Activity/ClassName לפני ניתוח טקסט. גם כאן ההיתר הוא scope-מדויק.
        String classScope = sensitiveScopeForClass(className);
        if (armed && classScope != null && !MagenGuard.allows(this, classScope)) {
            if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
            ServerEventReporter.report(this, "SENSITIVE_SETTINGS_BLOCKED", "HIGH",
                "class=" + className + " scope=" + classScope);
            block();
            return true;
        }

        if (isSettingsPackage(pkg)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    boolean ourApp = findText(root, "שומר הברית") ||
                                     findText(root, "Magen") ||
                                     findText(root, "magen.family");

                    boolean accessibilityScreen =
                        (ourApp && (findText(root, "השתמש בשירות") ||
                                    findText(root, "Use service") ||
                                    findText(root, "סינון תוכן"))) ||
                        containsIgnoreCase(className, "accessibility");
                    if (armed && accessibilityScreen) {
                        if (MagenGuard.allows(this, MagenGuard.SCOPE_ACCESSIBILITY)) return false;
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
                        ServerEventReporter.report(this, "ACCESSIBILITY_SETTINGS_BLOCKED", "HIGH", "Magen accessibility settings");
                        block();
                        return true;
                    }

                    boolean vpnConfigScreen =
                        findText(root, "VPN") || findText(root, "רשת פרטית וירטואלית") ||
                        findText(root, "Virtual private network") ||
                        containsIgnoreCase(className, "vpn");
                    if (armed && vpnConfigScreen) {
                        if (MagenGuard.allows(this, MagenGuard.SCOPE_VPN)) return false;
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
                        ServerEventReporter.report(this, "VPN_SETTINGS_BLOCKED", "HIGH", "VPN settings opened without VPN scope");
                        block();
                        return true;
                    }

                    boolean overlayScreen =
                        findText(root, "הצג מעל אפליקציות אחרות") ||
                        findText(root, "Display over other apps") ||
                        findText(root, "Appear on top") ||
                        findText(root, "הופעה מעל") ||
                        findText(root, "מוצג מעל");
                    if (armed && overlayScreen && ourApp) {
                        if (MagenGuard.allows(this, MagenGuard.SCOPE_OVERLAY)) return false;
                        ServerEventReporter.report(this, "OVERLAY_SETTINGS_BLOCKED", "HIGH", "overlay settings");
                        block();
                        return true;
                    }

                    boolean adminScreen =
                        findText(root, "ניהול מכשיר") || findText(root, "Device admin") ||
                        findText(root, "אפליקציות ניהול") || findText(root, "מנהלי מכשיר") ||
                        containsIgnoreCase(className, "deviceadmin") || containsIgnoreCase(className, "device_admin");
                    if (armed && adminScreen && ourApp) {
                        if (MagenGuard.allows(this, MagenGuard.SCOPE_DEVICE_ADMIN)) return false;
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
                        ServerEventReporter.report(this, "DEVICE_ADMIN_SETTINGS_BLOCKED", "HIGH", "device admin settings");
                        block();
                        return true;
                    }

                    boolean batteryScreen =
                        findText(root, "אופטימיזציית סוללה") ||
                        findText(root, "אופטימיזציה של סוללה") ||
                        findText(root, "Battery optimization") ||
                        findText(root, "Battery optimisation");
                    if (armed && batteryScreen && ourApp) {
                        if (MagenGuard.allows(this, MagenGuard.SCOPE_BATTERY)) return false;
                        ServerEventReporter.report(this, "BATTERY_SETTINGS_BLOCKED", "HIGH", "battery optimization settings");
                        block();
                        return true;
                    }

                    boolean usageScreen =
                        findText(root, "גישה לנתוני שימוש") || findText(root, "Usage access") ||
                        findText(root, "Usage data access");
                    if (armed && usageScreen && ourApp) {
                        if (MagenGuard.allows(this, MagenGuard.SCOPE_USAGE)) return false;
                        block();
                        return true;
                    }

                    // App Info / Permission Controller של Magen: מותר רק למסלול Restricted Settings
                    // המפורש ב-onboarding. overlay/battery מטופלים למעלה לפני הכלל הרחב הזה.
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
                                       findText(root, "Clear storage") ||
                                       findText(root, "הרשאות") ||
                                       findText(root, "Permissions");
                    if (armed && ourApp && (dangerous || isAppDetailsClass(className))) {
                        if (MagenGuard.allows(this, MagenGuard.SCOPE_APP_DETAILS)) return false;
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordUninstallAttempt();
                        ServerEventReporter.report(this, "PROTECTION_SETTINGS_BLOCKED", "HIGH", "Magen app details/permissions");
                        block();
                        return true;
                    }

                    // אפשרויות למפתחים — אין scope רגיל שמאפשר אותן. רק emergency recovery.
                    if (armed && (
                            findText(root, "שירותים פעילים") ||
                            findText(root, "Running services") ||
                            findText(root, "ניפוי באגים ב-USB") ||
                            findText(root, "USB debugging") ||
                            findText(root, "ביטול נעילה של OEM") ||
                            findText(root, "OEM unlocking"))) {
                        if (behaviorAnalyzer != null) behaviorAnalyzer.recordVpnBypassAttempt();
                        ServerEventReporter.report(this, "DEVELOPER_SETTINGS_BLOCKED", "HIGH", "developer options");
                        block();
                        return true;
                    }
                } finally {
                    root.recycle();
                }
            }
            return true;
        }

        // דיאלוג VPN של המערכת מותר רק כש-Magen עצמו פתח מסלול VPN מורשה.
        if (pkg.equals("com.android.vpndialogs") || pkg.contains("vpndialog")) {
            if (MagenGuard.allows(this, MagenGuard.SCOPE_VPN)) return false;
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
                        ServerEventReporter.report(this, "UNINSTALL_BLOCKED", "HIGH", "package installer");
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

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null &&
            value.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    private boolean isAppDetailsClass(String className) {
        if (className == null) return false;
        String c = className.toLowerCase(java.util.Locale.ROOT);
        return c.contains("appinfo") || c.contains("applicationdetail") ||
               c.contains("installedappdetail") || c.contains("manageapplications");
    }

    private String sensitiveScopeForClass(String className) {
        if (className == null) return null;
        String c = className.toLowerCase(java.util.Locale.ROOT);
        if (c.contains("accessibility")) return MagenGuard.SCOPE_ACCESSIBILITY;
        if (c.contains("deviceadmin") || c.contains("device_admin")) return MagenGuard.SCOPE_DEVICE_ADMIN;
        if (c.contains("vpn") && (c.contains("settings") || c.contains("dialog") || c.contains("profile")))
            return MagenGuard.SCOPE_VPN;
        if (c.contains("overlay") || c.contains("drawover") || c.contains("manageexternal"))
            return MagenGuard.SCOPE_OVERLAY;
        return null;
    }

    private boolean isSensitiveSystemScreen(String className) {
        return sensitiveScopeForClass(className) != null;
    }

    private void maybeClassifyTelegramVisibleText(AccessibilityNodeInfo root, String pkg, long now) {
        if (now - lastTelegramAiAt < TELEGRAM_AI_INTERVAL_MS) return;
        String text = collectVisibleText(root, 1800);
        if (text.length() < 8) return;
        int hash = text.hashCode();
        if (hash == lastTelegramTextHash) return;
        lastTelegramTextHash = hash;
        lastTelegramAiAt = now;

        RemoteIntelligenceClient.classifyTextAsync(getApplicationContext(), text, verdict -> {
            if (!Boolean.TRUE.equals(verdict)) return;
            mainHandler.post(() -> {
                AccessibilityNodeInfo current = getRootInActiveWindow();
                try {
                    if (current != null && current.getPackageName() != null
                            && TELEGRAM_PACKAGES.contains(current.getPackageName().toString())) {
                        lastContentBlockPkg = pkg;
                        lastContentBlockTime = System.currentTimeMillis();
                        ServerEventReporter.report(this, "TELEGRAM_AI_BLOCK", "HIGH", "DeepSeek classified visible Telegram text as adult");
                        block();
                    }
                } finally {
                    if (current != null) current.recycle();
                }
            });
        });
    }

    private String collectVisibleText(AccessibilityNodeInfo root, int maxChars) {
        StringBuilder out = new StringBuilder();
        collectVisibleText(root, out, 0, maxChars);
        return out.toString().trim();
    }

    private void collectVisibleText(AccessibilityNodeInfo node, StringBuilder out, int depth, int maxChars) {
        if (node == null || depth > MAX_SCAN_DEPTH || out.length() >= maxChars) return;
        CharSequence text = node.getText();
        if (!TextUtils.isEmpty(text)) appendEvidence(out, text.toString(), maxChars);
        CharSequence desc = node.getContentDescription();
        if (!TextUtils.isEmpty(desc)) appendEvidence(out, desc.toString(), maxChars);
        for (int i = 0; i < node.getChildCount() && out.length() < maxChars; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try { collectVisibleText(child, out, depth + 1, maxChars); }
            finally { if (child != null) child.recycle(); }
        }
    }

    private void appendEvidence(StringBuilder out, String value, int maxChars) {
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty()) return;
        if (out.length() > 0) out.append(" | ");
        int room = maxChars - out.length();
        if (room <= 0) return;
        out.append(v, 0, Math.min(v.length(), room));
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

    private void blockVisual(String pkg, NsfwResult result) {
        if (pkg == null || result == null) return;
        // Avoid a late classifier result blocking a different app after the user switched away.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String currentPkg = "";
        if (root != null) {
            try { if (root.getPackageName() != null) currentPkg = root.getPackageName().toString(); }
            finally { root.recycle(); }
        }
        if (!pkg.equals(currentPkg)) return;

        lastContentBlockPkg = pkg;
        lastContentBlockTime = System.currentTimeMillis();
        MagenVisualCurtain.show(this, result.label);
        ServerEventReporter.report(this, "VISUAL_BLOCK_LOCAL", "HIGH",
            "package=" + pkg + " " + result.compact());
        performGlobalAction(GLOBAL_ACTION_BACK);
        performGlobalAction(GLOBAL_ACTION_HOME);
        mainHandler.postDelayed(() -> {
            try {
                Intent i = new Intent(this, BlockedActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra("reason", "visual");
                startActivity(i);
            } catch (Exception ignored) {}
        }, 180L);
    }

    private void block() {
        com.magen.family.debug.DebugLog.log(this, "חסימה", "מסך נחסם והמשתמש הוחזר");
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
        if (pkg == null || pkg.isEmpty()) return false;
        String lower = pkg.toLowerCase(java.util.Locale.ROOT);
        return pkg.equals("com.android.settings") ||
               lower.contains(".settings") || lower.endsWith("settings") ||
               pkg.equals("com.coloros.settings") ||
               pkg.equals("com.oplus.settings") ||
               pkg.equals("com.miui.securitycenter") ||
               pkg.equals("com.samsung.android.lool") ||
               pkg.startsWith("com.android.settings") ||
               // מנהל ההרשאות של שיאומי/MIUI/HyperOS יושב בחבילה נפרדת לגמרי.
               // בלעדיה מסך ההרשאות בשיאומי כלל לא נבדק — פרצה במכשיר שלך.
               pkg.equals("com.lbe.security.miui") ||
               pkg.equals("com.miui.settings") ||
               pkg.contains("securitypermission") ||   // Oppo/Realme/OnePlus
               pkg.equals("com.oneplus.security") ||
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
        if (visualShield != null) { try { visualShield.close(); } catch (Exception ignored) {} visualShield = null; }
        MagenVisualCurtain.hide();
        super.onDestroy();
        // כיבוי שירות הנגישות = ליבת הסינון מתה. TamperWatcher כבר מזהה את זה
        // מיידית דרך ContentObserver ונועל, אבל מנסים גם להקים את שאר השכבות.
        try {
            startService(new Intent(this, FilterService.class));
        } catch (Exception ignored) {}
    }
}
