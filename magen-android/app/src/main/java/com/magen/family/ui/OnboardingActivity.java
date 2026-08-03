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
        Step(int t, int b, Check c, Launch l) { title = t; body = b; check = c; launch = l; }
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
        // 1. פתיחה
        steps.add(new Step(R.string.onb_welcome_title, R.string.onb_welcome_body, null, null));

        // 2. התראות (Android 13+)
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
            }));

        // 3. הצגה מעל אפליקציות
        steps.add(new Step(R.string.onb_perm_overlay_title, R.string.onb_perm_overlay_body,
            () -> Settings.canDrawOverlays(this),
            () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())))));

        // 4. VPN מקומי
        steps.add(new Step(R.string.onb_perm_vpn_title, R.string.onb_perm_vpn_body,
            () -> VpnService.prepare(this) == null,
            () -> {
                Intent prep = VpnService.prepare(this);
                if (prep != null) startActivityForResult(prep, 2001);
            }));

        // 5. נתוני שימוש
        steps.add(new Step(R.string.onb_perm_usage_title, R.string.onb_perm_usage_body,
            this::hasUsageAccess,
            () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));

        // 6. פטור סוללה
        steps.add(new Step(R.string.onb_perm_battery_title, R.string.onb_perm_battery_body,
            this::isBatteryExempt,
            () -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())))));

        // 7. מנהל מכשיר
        steps.add(new Step(R.string.onb_perm_admin_title, R.string.onb_perm_admin_body,
            () -> MagenDeviceAdmin.isAdminActive(this),
            () -> MagenDeviceAdmin.requestAdmin(this)));

        // 8. שירות נגישות — אחרון
        steps.add(new Step(R.string.onb_perm_accessibility_title,
            R.string.onb_perm_accessibility_body,
            this::isAccessibilityOn,
            () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
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
            if (s.launch != null) s.launch.go();
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
        tvStep.setText((index + 1) + " / " + steps.size());
        tvTitle.setText(s.title);
        tvBody.setText(s.body);

        boolean last = index == steps.size() - 1;
        boolean informational = s.check == null;
        boolean granted = !informational && s.check.granted();

        if (informational) {
            tvStatus.setText("");
            btnGrant.setVisibility(View.GONE);
            btnSkip.setVisibility(View.GONE);
        } else if (granted) {
            tvStatus.setText(R.string.onb_granted);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
            btnGrant.setVisibility(View.GONE);
            btnSkip.setVisibility(View.GONE);
        } else {
            tvStatus.setText("");
            btnGrant.setVisibility(View.VISIBLE);
            btnGrant.setText(R.string.onb_grant);
            btnSkip.setVisibility(View.VISIBLE);
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
