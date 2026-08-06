package com.magen.family.ui;

import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.magen.family.MagenApp;
import com.magen.family.R;
import com.magen.family.admin.MagenDeviceAdmin;

import java.util.ArrayList;
import java.util.List;

/**
 * OnboardingActivity — מדריך פתיחה שלב-אחר-שלב.
 *
 * למה זה קיים:
 *   קודם מסך ההגדרות זרק על המשתמש שורה של הרשאות רגישות (נגישות, VPN, מנהל
 *   מכשיר) בלי הסבר. זה הדבר מספר אחת שמפיל התקנות של אפליקציות כאלה — אנשים
 *   נבהלים מ"האפליקציה תוכל לקרוא את המסך" ומבטלים.
 *
 *   כאן כל הרשאה מקבלת שלב משלה: כותרת, הסבר בגובה העיניים למה היא נחוצה,
 *   כפתור הענקה, וסימון ✓ ברגע שהוענקה. השלב האחרון הוא הנגישות — כי ממנה
 *   ההגנה מתחילה לפעול בפועל.
 *
 * המדריך נבנה בקוד ולא ב-XML כדי לשמור על לוגיקת ה"שלב" פשוטה וגמישה.
 */
public class OnboardingActivity extends BaseActivity {

    private interface Check { boolean granted(); }
    private interface Launch { void go(); }

    private static class Step {
        final int title, body;
        final Check check;   // null = שלב אינפורמטיבי בלבד
        final Launch launch;
        final boolean required;   // הרשאה קריטית — אי אפשר להתקדם בלעדיה
        Step(int t, int b, Check c, Launch l, boolean req) {
            title = t; body = b; check = c; launch = l; required = req;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private int index = 0;

    private TextView tvStep, tvTitle, tvBody, tvStatus;
    private Button btnGrant, btnNext, btnSkip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildSteps();
        setContentView(buildLayout());
        render();
    }

    private void buildSteps() {
        // 1. פתיחה (אינפורמטיבי)
        steps.add(new Step(R.string.onb_welcome_title, R.string.onb_welcome_body,
            null, null, false));

        // 2. איך זה עובד (אינפורמטיבי)
        steps.add(new Step(R.string.onb_how_title, R.string.onb_how_body,
            null, null, false));

        // 3. התראות (Android 13+) — מומלץ
        steps.add(new Step(R.string.onb_perm_notif_title, R.string.onb_perm_notif_body,
            () -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                  ContextCompat.checkSelfPermission(this,
                      android.Manifest.permission.POST_NOTIFICATIONS)
                      == android.content.pm.PackageManager.PERMISSION_GRANTED,
            () -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 1001);
                }
            }, false));

        // 4. הצגה מעל אפליקציות — חובה (מסך החסימה)
        steps.add(new Step(R.string.onb_perm_overlay_title, R.string.onb_perm_overlay_body,
            () -> Settings.canDrawOverlays(this),
            () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))), true));

        // 5. VPN מקומי — חובה (סינון רשת)
        steps.add(new Step(R.string.onb_perm_vpn_title, R.string.onb_perm_vpn_body,
            () -> VpnService.prepare(this) == null,
            () -> {
                Intent prep = VpnService.prepare(this);
                if (prep != null) startActivityForResult(prep, 2001);
            }, true));

        // 6. נתוני שימוש — מומלץ
        steps.add(new Step(R.string.onb_perm_usage_title, R.string.onb_perm_usage_body,
            this::hasUsageAccess,
            () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)), false));

        // 7. פטור סוללה — חובה (שהשירות לא ייהרג ברקע)
        steps.add(new Step(R.string.onb_perm_battery_title, R.string.onb_perm_battery_body,
            this::isBatteryExempt,
            () -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))), true));

        // 7.5 הפעלה אוטומטית (Autostart) — רק ביצרנים שהורגים שירותים.
        //     אין API לבדוק אם ניתנה, לכן מסמנים "ביקרת במסך" (מומלץ, לא חובה).
        if (com.magen.family.service.OemAutostart.isLikelyNeeded()) {
            steps.add(new Step(R.string.onb_perm_autostart_title, R.string.onb_perm_autostart_body,
                () -> MagenApp.getInstance().getPrefs().getBoolean("autostart_opened", false),
                () -> {
                    MagenApp.getInstance().getPrefs().edit()
                        .putBoolean("autostart_opened", true).apply();
                    com.magen.family.service.OemAutostart.open(this);
                }, false));
        }

        // 8. מנהל מכשיר — חובה (הגנה מפני הסרה)
        steps.add(new Step(R.string.onb_perm_admin_title, R.string.onb_perm_admin_body,
            () -> MagenDeviceAdmin.isAdminActive(this),
            () -> MagenDeviceAdmin.requestAdmin(this), true));

        // 9. שירות נגישות — חובה, אחרון (ליבת הסינון)
        steps.add(new Step(R.string.onb_perm_accessibility_title,
            R.string.onb_perm_accessibility_body,
            this::isAccessibilityOn,
            () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), true));

        // 10. סיום — ההגנה פעילה (אינפורמטיבי)
        steps.add(new Step(R.string.onb_done_title, R.string.onb_done_body,
            null, null, false));
    }

    // ---------------- UI ----------------

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_light));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        root.setPadding(pad, dp(32), pad, pad);

        tvStep = new TextView(this);
        tvStep.setTextSize(13);
        tvStep.setTextColor(ContextCompat.getColor(this, R.color.accent));
        root.addView(tvStep);

        tvTitle = new TextView(this);
        tvTitle.setTextSize(26);
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tvTitle.setPadding(0, dp(10), 0, dp(12));
        root.addView(tvTitle);

        tvBody = new TextView(this);
        tvBody.setTextSize(16);
        tvBody.setLineSpacing(dp(4), 1f);
        tvBody.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        root.addView(tvBody);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(15);
        tvStatus.setPadding(0, dp(16), 0, 0);
        root.addView(tvStatus);

        btnGrant = new Button(this);
        btnGrant.setAllCaps(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(24);
        btnGrant.setLayoutParams(glp);
        btnGrant.setOnClickListener(v -> {
            Step s = steps.get(index);
            if (s.launch == null) return;
            // חייב להיות עטוף: מסכי הרשאה של המערכת לא קיימים בכל ROM
            // (ActivityNotFoundException ודומיו), וקריסה כאן הפילה את כל
            // ההתקנה בשלב מנהל המכשיר.
            try {
                s.launch.go();
            } catch (Exception e) {
                android.widget.Toast.makeText(this,
                    getString(R.string.onb_open_failed), android.widget.Toast.LENGTH_LONG).show();
            }
        });
        root.addView(btnGrant);

        btnNext = new Button(this);
        btnNext.setAllCaps(false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        btnNext.setLayoutParams(nlp);
        btnNext.setOnClickListener(v -> advance());
        root.addView(btnNext);

        btnSkip = new Button(this);
        btnSkip.setAllCaps(false);
        btnSkip.setBackgroundColor(0x00000000);
        btnSkip.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        btnSkip.setText(R.string.onb_skip);
        btnSkip.setOnClickListener(v -> advance());
        root.addView(btnSkip);

        TextView transparency = new TextView(this);
        transparency.setText(R.string.onb_transparency);
        transparency.setTextSize(12);
        transparency.setGravity(Gravity.CENTER);
        transparency.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        transparency.setPadding(0, dp(28), 0, 0);
        root.addView(transparency);

        scroll.addView(root);
        return scroll;
    }

    private void render() {
        Step s = steps.get(index);
        tvTitle.setText(s.title);
        tvBody.setText(s.body);

        boolean last = index == steps.size() - 1;
        boolean informational = s.check == null;
        boolean granted = !informational && s.check.granted();

        // שורת מונה + תג "חובה"/"מומלץ"
        String counter = (index + 1) + " / " + steps.size();
        if (!informational) {
            counter += "   •   " + getString(
                s.required ? R.string.onb_required_badge : R.string.onb_optional_badge);
        }
        tvStep.setText(counter);

        if (informational) {
            tvStatus.setText("");
            btnGrant.setVisibility(View.GONE);
            btnSkip.setVisibility(View.GONE);
            btnNext.setVisibility(View.VISIBLE);
        } else if (granted) {
            tvStatus.setText(R.string.onb_granted);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
            btnGrant.setVisibility(View.GONE);
            btnSkip.setVisibility(View.GONE);
            btnNext.setVisibility(View.VISIBLE);
        } else if (s.required) {
            // הרשאה קריטית שעוד לא הוענקה — אי אפשר להתקדם.
            tvStatus.setText(R.string.onb_required);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent));
            btnGrant.setVisibility(View.VISIBLE);
            btnGrant.setText(R.string.onb_grant);
            btnSkip.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
        } else {
            // הרשאה מומלצת — אפשר לדלג.
            tvStatus.setText("");
            btnGrant.setVisibility(View.VISIBLE);
            btnGrant.setText(R.string.onb_grant);
            btnSkip.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
        }

        btnNext.setText(last ? R.string.onb_finish : R.string.next);
    }

    private void advance() {
        if (index < steps.size() - 1) {
            index++;
            render();
        } else {
            finishOnboarding();
        }
    }

    private void finishOnboarding() {
        // הגנה: onboarding_done נדלק *רק* כשכל ההרשאות ה"חובה" פעילות — כי
        // ההגנה העצמית (חסימת מסך ההרשאות/הסוללה) מותנית בדגל הזה. אם משהו
        // קריטי כובה בין לבין, קופצים חזרה לשלב החסר במקום לסיים.
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.required && s.check != null && !s.check.granted()) {
                index = i;
                render();
                android.widget.Toast.makeText(this, R.string.onb_missing,
                    android.widget.Toast.LENGTH_LONG).show();
                return;
            }
        }
        MagenApp.getInstance().getPrefs().edit()
            .putBoolean("onboarding_done", true).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();   // מרענן סטטוס אחרי חזרה ממסך הרשאה
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        render();
    }

    // ---------------- בדיקות מצב ----------------

    private boolean hasUsageAccess() {
        try {
            android.app.AppOpsManager aom =
                (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    private boolean isBatteryExempt() {
        try {
            android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) { return false; }
    }

    private boolean isAccessibilityOn() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return !TextUtils.isEmpty(enabled) && enabled.contains(getPackageName());
        } catch (Exception e) { return false; }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
