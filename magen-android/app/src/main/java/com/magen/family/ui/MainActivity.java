package com.magen.family.ui;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.magen.family.MagenApp;
import com.magen.family.MagenConfig;
import com.magen.family.R;
import com.magen.family.admin.MagenDeviceAdmin;
import com.magen.family.filter.HostAllowList;
import com.magen.family.service.FilterService;
import com.magen.family.service.MagenVpnService;
import com.magen.family.service.NightModeService;
import com.magen.family.service.vpn.VpnPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends BaseActivity {

    private static final int REQ_PIN_TOGGLE    = 102;
    private static final int REQ_PIN_ADMIN     = 103;
    private static final int REQ_PIN_CHANGE1   = 104;
    private static final int REQ_PIN_CHANGE2   = 105;
    private static final int REQ_PIN_APP_BLOCK = 106;
    private static final int REQ_ADMIN         = 107;
    private static final int REQ_VPN           = 108;
    private static final int REQ_PIN_NIGHT     = 109;
    private static final int REQ_PIN_STATS       = 110;
    private static final int REQ_PIN_SCREEN_TIME = 111;
    private static final int REQ_PIN_ADVANCED    = 112;

    private android.widget.CompoundButton swMain;
    private TextView tvStatus, tvAdminStatus, tvBlockedCount, tvBlockedAppsCount;
    private ListView lvApps;
    private EditText etSearch;

    private List<AppItem> allApps = new ArrayList<>();
    private List<AppItem> filteredApps = new ArrayList<>();
    private AppListAdapter adapter;

    private String pendingBlockPackage = null;
    private boolean pendingBlockState  = false;
    private boolean ignoreToggle = false;

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = MagenDeviceAdmin.getComponentName(this);

        // הרשאות חסרות מבוקשות אחת בכל פעם, ורק בהפעלה הראשונה של המסך.
        // קודם onCreate פתח את מסך ה-overlay *וגם* את דיאלוג הסוללה בו-זמנית,
        // ו-onResume פתח שוב את מסך ה-overlay בכל חזרה — לולאה שממנה המשתמש
        // לא יכול היה לצאת ולא הספיק אפילו לקרוא את ההודעה.
        if (savedInstanceState == null) requestNextMissingPermission();

        startFilterService();
        startVpn();
        NightModeService.schedule(this);
        startService(new Intent(this, com.magen.family.service.ScreenTimeService.class));
        startService(new Intent(this, com.magen.family.service.AppScheduleService.class));
        startService(new Intent(this, com.magen.family.service.GeofenceService.class));
        startService(new Intent(this, com.magen.family.service.TamperDetectorService.class));
        com.magen.family.service.MagenWatchdogJob.schedule(this);
        initViews();
        updateAdvancedSubtitle();
        loadAppList();
    }

    private void initViews() {
        swMain             = findViewById(R.id.sw_filter);
        tvStatus           = findViewById(R.id.tv_status);
        tvAdminStatus      = findViewById(R.id.tv_admin_status);
        tvBlockedCount     = findViewById(R.id.tv_blocked_count);
        tvBlockedAppsCount = findViewById(R.id.tv_blocked_apps_count);
        lvApps             = findViewById(R.id.lv_apps);
        etSearch           = findViewById(R.id.et_search_apps);

        refreshStatus();

        // מתג ON/OFF
        swMain.setOnCheckedChangeListener((v, checked) -> {
            if (ignoreToggle) return;
            ignoreToggle = true;
            swMain.setChecked(!checked);
            ignoreToggle = false;
            askPin(REQ_PIN_TOGGLE);
        });

        // כפתורים
        findViewById(R.id.btn_admin).setOnClickListener(v -> askPin(REQ_PIN_ADMIN));
        findViewById(R.id.btn_accessibility).setOnClickListener(v ->
            com.magen.family.util.SafeLaunch.openAction(this,
                Settings.ACTION_ACCESSIBILITY_SETTINGS));
        findViewById(R.id.btn_change_pin).setOnClickListener(v -> askPin(REQ_PIN_CHANGE1));
findViewById(R.id.btn_screen_time).setOnClickListener(v -> askPin(REQ_PIN_SCREEN_TIME));
        findViewById(R.id.btn_night).setOnClickListener(v -> askPin(REQ_PIN_NIGHT));
        findViewById(R.id.btn_stats).setOnClickListener(v -> askPin(REQ_PIN_STATS));
        findViewById(R.id.btn_lockout).setOnClickListener(v -> showLockoutDialog());
        findViewById(R.id.btn_advanced).setOnClickListener(v -> askPin(REQ_PIN_ADVANCED));
        // מרכז הברית פתוח בלי PIN — הוא של המשתמש עצמו, לא הגדרת אכיפה
        findViewById(R.id.btn_covenant).setOnClickListener(v ->
            startActivity(new Intent(this, CovenantCenterActivity.class)));

        // חיפוש אפליקציות
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterApps(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void askPin(int requestCode) {
        String mode = requestCode == REQ_PIN_CHANGE2 ? "change" : "verify";
        startActivityForResult(
            new Intent(this, PinActivity.class).putExtra("mode", mode),
            requestCode
        );
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_VPN && res == RESULT_OK) {
            startService(new Intent(this, MagenVpnService.class));
            return;
        }

        if (res != RESULT_OK) return;

        if (req == REQ_PIN_TOGGLE) {
            boolean next = !MagenApp.getInstance().isFilterEnabled();
            MagenApp.getInstance().getPrefs()
                .edit().putBoolean(MagenApp.KEY_FILTER_ENABLED, next).apply();
            refreshStatus();

        } else if (req == REQ_PIN_ADMIN) {
            requestAdmin();

        } else if (req == REQ_PIN_CHANGE1) {
            askPin(REQ_PIN_CHANGE2);

        } else if (req == REQ_PIN_CHANGE2) {
            Toast.makeText(this, "✓ הקוד עודכן!", Toast.LENGTH_SHORT).show();

} else if (req == REQ_PIN_SCREEN_TIME) {
            startActivity(new Intent(this, ScreenTimeActivity.class));
        } else if (req == REQ_PIN_NIGHT) {
            startActivity(new Intent(this, NightModeActivity.class));

        } else if (req == REQ_PIN_STATS) {
            startActivity(new Intent(this, DashboardActivity.class));

        } else if (req == REQ_PIN_APP_BLOCK && pendingBlockPackage != null) {
            MagenConfig.setAppBlocked(this, pendingBlockPackage, pendingBlockState);
            setAppVisibility(pendingBlockPackage, pendingBlockState);
            for (AppItem item : allApps) {
                if (item.packageName.equals(pendingBlockPackage))
                    item.blocked = pendingBlockState;
            }
            updateBlockedAppsCount();
            filterApps(etSearch.getText().toString());
            pendingBlockPackage = null;

        } else if (req == REQ_PIN_ADVANCED) {
            showAdvancedDialog();

        } else if (req == REQ_ADMIN) {
            refreshStatus();
        }
    }

    private void filterApps(String query) {
        filteredApps.clear();
        String q = query.toLowerCase().trim();
        for (AppItem item : allApps) {
            if (q.isEmpty() || item.name.toLowerCase().contains(q)) {
                filteredApps.add(item);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateBlockedAppsCount() {
        int count = 0;
        for (AppItem item : allApps) if (item.blocked) count++;
        if (tvBlockedAppsCount != null)
            tvBlockedAppsCount.setText(count + " חסומות");
    }

    private void setAppVisibility(String pkg, boolean hide) {
        try {
            if (dpm != null && (dpm.isProfileOwnerApp(getPackageName()) ||
                dpm.isDeviceOwnerApp(getPackageName()))) {
                dpm.setApplicationHidden(adminComponent, pkg, hide);
            }
        } catch (Exception ignored) {}
    }

    private void requestAdmin() {
        if (dpm != null && dpm.isAdminActive(adminComponent)) {
            Toast.makeText(this, "✓ ההגנה כבר פעילה", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "נדרש למנוע מחיקה ללא אישורך");
        startActivityForResult(i, REQ_ADMIN);
    }

    private void refreshStatus() {
        boolean on = MagenApp.getInstance().isFilterEnabled();
        ignoreToggle = true;
        if (swMain != null) swMain.setChecked(on);
        ignoreToggle = false;
        if (tvStatus != null) tvStatus.setText(on ? "ההגנה פעילה" : "ההגנה כבויה");
        int cnt = MagenApp.getInstance().getPrefs().getInt(MagenApp.KEY_BLOCKED_COUNT, 0);
        if (tvBlockedCount != null) tvBlockedCount.setText(String.valueOf(cnt));
        boolean adminOn = dpm != null && dpm.isAdminActive(adminComponent);
        if (tvAdminStatus != null) tvAdminStatus.setText(adminOn ? "✓ פעילה" : "⚠ לא פעילה");
    }

    private void loadAppList() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            allApps.clear();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            // מיין — חסומות קודם
            for (ApplicationInfo app : installed) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (MagenConfig.isWhitelisted(app.packageName)) continue;
                AppItem item = new AppItem();
                item.packageName = app.packageName;
                item.name = pm.getApplicationLabel(app).toString();
                item.icon = app.loadIcon(pm);
                item.blocked = MagenConfig.isAppBlockedByUser(this, app.packageName);
                allApps.add(item);
            }
            // מיין: חסומות קודם, אחר כך A-Z
            Collections.sort(allApps, (a, b) -> {
                if (a.blocked != b.blocked) return a.blocked ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            filteredApps.addAll(allApps);
            runOnUiThread(() -> {
                adapter = new AppListAdapter(filteredApps);
                if (lvApps != null) lvApps.setAdapter(adapter);
                updateBlockedAppsCount();
            });
        }).start();
    }

    private void startVpn() {
        Intent vpnIntent = android.net.VpnService.prepare(this);
        if (vpnIntent != null) startActivityForResult(vpnIntent, REQ_VPN);
        else startService(new Intent(this, MagenVpnService.class));
    }

    private void startFilterService() {
        Intent i = new Intent(this, FilterService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshOverlayWarning();
        // סנכרון ממותנן של משפטי החיזוק — אישי (בוט) + משותף (ערוץ ציבורי)
        com.magen.family.service.TelegramNotifier.syncSentencesAsync(this);
        com.magen.family.service.GlobalSentences.syncAsync(this);
        // בדיקה אם הגנה קריטית כובתה (Safe Mode / נגישות / מנהל / overlay)
        com.magen.family.service.ProtectionWatch.checkAsync(this);
    }

    /** מציג באנר קבוע במקום לפתוח את מסך ההרשאה שוב ושוב. */
    private void refreshOverlayWarning() {
        View warning = findViewById(R.id.tv_overlay_warning);
        if (warning == null) return;
        boolean missing = !android.provider.Settings.canDrawOverlays(this);
        warning.setVisibility(missing ? View.VISIBLE : View.GONE);
        if (missing) warning.setOnClickListener(v -> openOverlaySettings());
    }

    private void openOverlaySettings() {
        try {
            Intent oi = new Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName()));
            startActivity(oi);
        } catch (Exception ignored) {}
    }

    /**
     * מבקש את ההרשאה החסרה הבאה — אחת בלבד, לפי סדר חשיבות.
     * שאר ההרשאות מבוקשות בפעם הבאה שהמסך נפתח, או דרך הבאנר.
     */
    private void requestNextMissingPermission() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
    }
    @Override public void onBackPressed() { moveTaskToBack(true); }

    // ===== App Item =====

    // ===== דיאלוג הגנה מתקדמת =====

    /**
     * המסך שממנו מפעילים את שכבות ההגנה החזקות.
     *
     * "סינון מלא" הוא ההגדרה המשמעותית ביותר באפליקציה: הוא מנתב את *כל*
     * תעבורת המכשיר דרך המסנן, מה שסוגר את האפשרות לעקוף אותו על ידי הפניית
     * שאילתות DNS לשרת אחר, ומאפשר חסימה לפי שם הדומיין בתוך חיבורי HTTPS.
     * הוא כבוי כברירת מחדל כי הוא גם הרכיב שהכי חשוב לבדוק על מכשיר אמיתי.
     */
    private void showAdvancedDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, 0);

        final android.widget.CheckBox cbFullTunnel = new android.widget.CheckBox(this);
        cbFullTunnel.setText("סינון מלא (מומלץ — בדקי אחרי הפעלה)");
        cbFullTunnel.setChecked(VpnPolicy.fullTunnel());
        root.addView(cbFullTunnel);

        TextView hint = new TextView(this);
        hint.setText("מנתב את כל התעבורה דרך המסנן: חוסם גם DNS חלופי וגם "
                   + "לפי שם אתר בתוך HTTPS. אם משהו ברשת מפסיק לעבוד — כבי את זה.");
        hint.setTextSize(12);
        hint.setTextColor(0xFF9E9E9E);
        hint.setPadding(0, 0, 0, pad / 2);
        root.addView(hint);

        final android.widget.CheckBox cbQuic = new android.widget.CheckBox(this);
        cbQuic.setText(R.string.adv_block_quic);
        cbQuic.setChecked(VpnPolicy.blockQuic());
        root.addView(cbQuic);

        final android.widget.CheckBox cbHotspot = new android.widget.CheckBox(this);
        cbHotspot.setText(R.string.adv_hotspot);
        cbHotspot.setChecked(VpnPolicy.blockHotspot());
        root.addView(cbHotspot);

        TextView hotspotHint = new TextView(this);
        hotspotHint.setText(R.string.adv_hotspot_hint);
        hotspotHint.setTextSize(12);
        hotspotHint.setTextColor(0xFF9E9E9E);
        hotspotHint.setPadding(0, 0, 0, pad / 2);
        root.addView(hotspotHint);

        // ---- טלגרם ----
        TextView tgLabel = new TextView(this);
        tgLabel.setText(R.string.tg_title);
        tgLabel.setTextColor(0xFF1A1F33);
        tgLabel.setTextSize(15);
        tgLabel.setPadding(0, pad / 2, 0, 0);
        root.addView(tgLabel);

        TextView tgIntro = new TextView(this);
        tgIntro.setText(R.string.tg_intro);
        tgIntro.setTextSize(12);
        tgIntro.setTextColor(0xFF9E9E9E);
        root.addView(tgIntro);

        final EditText etTgToken = new EditText(this);
        etTgToken.setHint(R.string.tg_token);
        etTgToken.setText(com.magen.family.service.TelegramNotifier.getToken(this));
        root.addView(etTgToken);

        final EditText etTgChat = new EditText(this);
        etTgChat.setHint(R.string.tg_chat);
        etTgChat.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
            | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        etTgChat.setText(com.magen.family.service.TelegramNotifier.getChatId(this));
        root.addView(etTgChat);

        Button btnTgValidate = new Button(this);
        btnTgValidate.setAllCaps(false);
        btnTgValidate.setText(R.string.tg_validate);
        root.addView(btnTgValidate);
        btnTgValidate.setOnClickListener(v ->
            validateTelegram(etTgToken.getText().toString(), etTgChat.getText().toString(), etTgChat));

        // ---- משפטי חיזוק (מסונכרנים מהצ'אט) ----
        TextView chizukIntro = new TextView(this);
        chizukIntro.setText(R.string.tg_sent_intro);
        chizukIntro.setTextSize(12);
        chizukIntro.setTextColor(0xFF9E9E9E);
        chizukIntro.setPadding(0, pad / 2, 0, 0);
        root.addView(chizukIntro);

        // ---- ערוץ ציבורי משותף (כולם קוראים) ----
        TextView chIntro = new TextView(this);
        chIntro.setText(R.string.ch_intro);
        chIntro.setTextSize(12);
        chIntro.setTextColor(0xFF9E9E9E);
        chIntro.setPadding(0, pad / 2, 0, 0);
        root.addView(chIntro);

        final EditText etChannel = new EditText(this);
        etChannel.setHint(R.string.ch_hint);
        etChannel.setText(com.magen.family.service.GlobalSentences.getChannel(this));
        root.addView(etChannel);

        Button btnSyncNow = new Button(this);
        btnSyncNow.setAllCaps(false);
        btnSyncNow.setText(R.string.tg_sync_now);
        root.addView(btnSyncNow);
        btnSyncNow.setOnClickListener(v -> {
            // שומרים את שם הערוץ שהוקלד (יכול להיות ריק אם משתמשים רק בבוט)
            com.magen.family.service.GlobalSentences.setChannel(this,
                etChannel.getText().toString());
            boolean hasBot = com.magen.family.service.TelegramNotifier.isConfigured(this);
            boolean hasChannel =
                !com.magen.family.service.GlobalSentences.getChannel(this).isEmpty();
            if (!hasBot && !hasChannel) {
                Toast.makeText(this, R.string.tg_not_configured, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, R.string.tg_validating, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                int addedBot = hasBot ? com.magen.family.service.TelegramNotifier
                    .syncSentencesBlocking(getApplicationContext()) : 0;
                int glob = hasChannel ? com.magen.family.service.GlobalSentences
                    .syncBlocking(getApplicationContext()) : 0;
                int total = com.magen.family.service.FallSentences.count(getApplicationContext())
                    + com.magen.family.service.FallSentences.countGlobal(getApplicationContext());
                runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.tg_sync_result, addedBot + glob, total),
                    Toast.LENGTH_LONG).show());
            }, "ChizukSyncNow").start();
        });

        Button btnViewSent = new Button(this);
        btnViewSent.setAllCaps(false);
        btnViewSent.setText(R.string.tg_sent_view);
        root.addView(btnViewSent);
        btnViewSent.setOnClickListener(v -> showSentencesDialog());

        // ---- טלפון שותף (SMS) ----
        TextView phoneLabel = new TextView(this);
        phoneLabel.setText(R.string.partner_phone);
        phoneLabel.setPadding(0, pad / 2, 0, 0);
        root.addView(phoneLabel);

        final EditText etPhone = new EditText(this);
        etPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        etPhone.setHint("05X-XXXXXXX");
        etPhone.setText(MagenApp.getInstance().getPrefs()
            .getString(MagenApp.KEY_PARENT_PHONE, ""));
        root.addView(etPhone);

        TextView phoneHint = new TextView(this);
        phoneHint.setText(R.string.partner_phone_hint);
        phoneHint.setTextSize(12);
        phoneHint.setTextColor(0xFF9E9E9E);
        root.addView(phoneHint);

        // ---- always-on VPN ----
        TextView aoHint = new TextView(this);
        aoHint.setText(R.string.adv_always_on_hint);
        aoHint.setTextSize(12);
        aoHint.setTextColor(0xFF9E9E9E);
        aoHint.setPadding(0, pad / 2, 0, 0);
        root.addView(aoHint);

        Button btnAlwaysOn = new Button(this);
        btnAlwaysOn.setAllCaps(false);
        btnAlwaysOn.setText(R.string.adv_always_on);
        root.addView(btnAlwaysOn);
        btnAlwaysOn.setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.settings.VPN_SETTINGS"));
            } catch (Exception e) {
                Toast.makeText(this, "Settings → Network → VPN", Toast.LENGTH_LONG).show();
            }
        });

        // ---- קיצורים: סינון תוכן + הסוואה + שפה ----
        Button btnContent = new Button(this);
        btnContent.setAllCaps(false);
        btnContent.setText(R.string.main_content_filter);
        btnContent.setPadding(0, pad / 2, 0, 0);
        root.addView(btnContent);
        btnContent.setOnClickListener(v -> showContentFilterDialog());

        Button btnDisguise = new Button(this);
        btnDisguise.setAllCaps(false);
        btnDisguise.setText(R.string.main_disguise);
        btnDisguise.setPadding(0, pad / 2, 0, 0);
        root.addView(btnDisguise);
        btnDisguise.setOnClickListener(v ->
            startActivity(new Intent(this, DisguiseActivity.class)));

        Button btnLang = new Button(this);
        btnLang.setAllCaps(false);
        btnLang.setText(R.string.main_language);
        root.addView(btnLang);
        btnLang.setOnClickListener(v -> showLanguageDialog());

        Button btnBackup = new Button(this);
        btnBackup.setAllCaps(false);
        btnBackup.setText(R.string.main_backup);
        root.addView(btnBackup);
        btnBackup.setOnClickListener(v -> showBackupDialog());

        Button btnUpdate = new Button(this);
        btnUpdate.setAllCaps(false);
        btnUpdate.setText(R.string.main_update);
        root.addView(btnUpdate);
        btnUpdate.setOnClickListener(v -> showUpdateDialog());

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.adv_title)
            .setView(scroll)
            .setPositiveButton(R.string.save, (d, w) -> {
                boolean wantFullTunnel = cbFullTunnel.isChecked();
                boolean changed = wantFullTunnel != VpnPolicy.fullTunnel();

                VpnPolicy.setFullTunnel(this, wantFullTunnel);
                VpnPolicy.setBlockQuic(this, cbQuic.isChecked());
                VpnPolicy.setBlockHotspot(this, cbHotspot.isChecked());

                String phone = etPhone.getText().toString().trim();
                MagenApp.getInstance().getPrefs().edit()
                    .putString(MagenApp.KEY_PARENT_PHONE, phone).apply();
                if (!phone.isEmpty()) requestSmsPermissionIfNeeded();

                if (changed) restartVpn();

                updateAdvancedSubtitle();
                Toast.makeText(this, "✓", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(R.string.adv_allowlist, (d, w) -> showAllowListDialog())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /** מציג את משפטי החיזוק שסונכרנו (משותפים + אישיים), עם אפשרות למחוק. */
    private void showSentencesDialog() {
        java.util.List<String> personal =
            com.magen.family.service.FallSentences.getAll(getApplicationContext());
        java.util.List<String> global =
            com.magen.family.service.FallSentences.getAllGlobal(getApplicationContext());
        int totalCount = personal.size() + global.size();
        CharSequence msg;
        if (totalCount == 0) {
            msg = getString(R.string.tg_sent_empty);
        } else {
            StringBuilder sb = new StringBuilder();
            if (!global.isEmpty()) {
                sb.append("🌐 ").append(getString(R.string.ch_shared_label)).append("\n");
                for (int i = 0; i < global.size(); i++)
                    sb.append(i + 1).append(". ").append(global.get(i)).append("\n\n");
            }
            if (!personal.isEmpty()) {
                sb.append("👤 ").append(getString(R.string.ch_personal_label)).append("\n");
                for (int i = 0; i < personal.size(); i++)
                    sb.append(i + 1).append(". ").append(personal.get(i)).append("\n\n");
            }
            msg = sb.toString().trim();
        }
        android.widget.TextView tv = new android.widget.TextView(this);
        int p = (int) (18 * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        tv.setTextSize(15);
        tv.setText(msg);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(tv);

        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.tg_sent_title, totalCount))
            .setView(sc)
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.tg_sent_clear, (d, w) ->
                new android.app.AlertDialog.Builder(this)
                    .setMessage(R.string.tg_sent_clear_confirm)
                    .setPositiveButton(R.string.tg_sent_clear, (d2, w2) -> {
                        com.magen.family.service.FallSentences.clear(getApplicationContext());
                        Toast.makeText(this, "✓", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show())
            .show();
    }

    /** מאמת מפתח טלגרם ברקע ושומר רק אם הצליח. */
    private void validateTelegram(String token, String chatHint, EditText chatField) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage(getString(R.string.tg_validating));
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            com.magen.family.service.TelegramNotifier.ValidationResult r =
                com.magen.family.service.TelegramNotifier.validate(token, chatHint);
            runOnUiThread(() -> {
                pd.dismiss();
                if (r.ok) {
                    String chatId = r.resolvedChatId != null ? r.resolvedChatId : chatHint.trim();
                    com.magen.family.service.TelegramNotifier.save(this, token, chatId, true);
                    if (chatField != null && r.resolvedChatId != null)
                        chatField.setText(r.resolvedChatId);
                    // סנכרון ראשוני של משפטי החיזוק שכבר נכתבו בצ'אט
                    new Thread(() ->
                        com.magen.family.service.TelegramNotifier.syncSentencesBlocking(
                            getApplicationContext()), "TgSyncInit").start();
                    Toast.makeText(this, getString(R.string.tg_ok, r.message),
                        Toast.LENGTH_LONG).show();
                } else {
                    // לא שומרים מפתח לא תקין — בדיוק כפי שהתבקש
                    Toast.makeText(this, getString(R.string.tg_fail, r.message),
                        Toast.LENGTH_LONG).show();
                }
            });
        }, "TgValidate").start();
    }

    /** מסך סינון תוכן — רמה, קטגוריות, חיפוש בטוח, DeepSeek. */
    private void showContentFilterDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, 0);

        // רמת סינון
        TextView lvlLabel = new TextView(this);
        lvlLabel.setText(R.string.cf_level);
        lvlLabel.setTextColor(0xFF1A1F33);
        root.addView(lvlLabel);

        final android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        int[] lvlStrings = { R.string.cf_level_light, R.string.cf_level_medium, R.string.cf_level_strict };
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(i);
            rb.setText(lvlStrings[i]);
            rg.addView(rb);
        }
        rg.check(com.magen.family.filter.FilterPolicy.getLevel(this));
        root.addView(rg);

        // קטגוריות
        TextView catLabel = new TextView(this);
        catLabel.setText(R.string.cf_categories);
        catLabel.setTextColor(0xFF1A1F33);
        catLabel.setPadding(0, pad / 2, 0, 0);
        root.addView(catLabel);

        final String[] catKeys = {
            com.magen.family.filter.FilterPolicy.CAT_ADULT,
            com.magen.family.filter.FilterPolicy.CAT_GAMBLING,
            com.magen.family.filter.FilterPolicy.CAT_DATING,
            com.magen.family.filter.FilterPolicy.CAT_SOCIAL,
            com.magen.family.filter.FilterPolicy.CAT_SHOPPING };
        final int[] catStrings = {
            R.string.cf_cat_adult, R.string.cf_cat_gambling, R.string.cf_cat_dating,
            R.string.cf_cat_social, R.string.cf_cat_shopping };
        final CheckBox[] catBoxes = new CheckBox[catKeys.length];
        for (int i = 0; i < catKeys.length; i++) {
            catBoxes[i] = new CheckBox(this);
            catBoxes[i].setText(catStrings[i]);
            catBoxes[i].setChecked(
                com.magen.family.filter.FilterPolicy.isCategoryOn(this, catKeys[i]));
            root.addView(catBoxes[i]);
        }

        // חיפוש בטוח + YouTube
        final CheckBox cbSafe = new CheckBox(this);
        cbSafe.setText(R.string.cf_safe_search);
        cbSafe.setChecked(com.magen.family.filter.SafeSearchEnforcer.isSafeSearchOn(this));
        cbSafe.setPadding(0, pad / 2, 0, 0);
        root.addView(cbSafe);

        final CheckBox cbYt = new CheckBox(this);
        cbYt.setText(R.string.cf_youtube);
        cbYt.setChecked(com.magen.family.filter.SafeSearchEnforcer.isYoutubeRestrictOn(this));
        root.addView(cbYt);

        // DeepSeek
        TextView dsLabel = new TextView(this);
        dsLabel.setText(R.string.ds_title);
        dsLabel.setTextColor(0xFF1A1F33);
        dsLabel.setPadding(0, pad / 2, 0, 0);
        root.addView(dsLabel);

        TextView dsIntro = new TextView(this);
        dsIntro.setText(R.string.ds_intro);
        dsIntro.setTextSize(12);
        dsIntro.setTextColor(0xFF9E9E9E);
        root.addView(dsIntro);

        TextView dsPriv = new TextView(this);
        dsPriv.setText(R.string.ds_privacy);
        dsPriv.setTextSize(12);
        dsPriv.setTextColor(0xFFEF4444);
        root.addView(dsPriv);

        final EditText etDsKey = new EditText(this);
        etDsKey.setHint(R.string.ds_key);
        etDsKey.setText(com.magen.family.filter.DeepSeekClassifier.getKey(this));
        root.addView(etDsKey);

        final CheckBox cbDs = new CheckBox(this);
        cbDs.setText(R.string.ds_enable);
        cbDs.setChecked(com.magen.family.filter.DeepSeekClassifier.isEnabled(this));
        root.addView(cbDs);

        Button btnDsValidate = new Button(this);
        btnDsValidate.setAllCaps(false);
        btnDsValidate.setText(R.string.ds_validate);
        root.addView(btnDsValidate);
        btnDsValidate.setOnClickListener(v ->
            validateDeepSeek(etDsKey.getText().toString(), cbDs));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.main_content_filter)
            .setView(scroll)
            .setPositiveButton(R.string.save, (d, w) -> {
                com.magen.family.filter.FilterPolicy.setLevel(this, rg.getCheckedRadioButtonId());
                for (int i = 0; i < catKeys.length; i++) {
                    com.magen.family.filter.FilterPolicy.setCategory(this, catKeys[i],
                        catBoxes[i].isChecked());
                }
                com.magen.family.filter.SafeSearchEnforcer.setSafeSearch(this, cbSafe.isChecked());
                com.magen.family.filter.SafeSearchEnforcer.setYoutubeRestrict(this, cbYt.isChecked());
                com.magen.family.filter.DeepSeekClassifier.save(this,
                    etDsKey.getText().toString(), cbDs.isChecked());
                com.magen.family.filter.DomainVerdict.clearCache();
                Toast.makeText(this, "✓", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void validateDeepSeek(String key, CheckBox enableBox) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage(getString(R.string.ds_validate));
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            boolean ok = com.magen.family.filter.DeepSeekClassifier.validate(key);
            runOnUiThread(() -> {
                pd.dismiss();
                if (ok) {
                    com.magen.family.filter.DeepSeekClassifier.save(this, key, true);
                    enableBox.setChecked(true);
                    Toast.makeText(this, R.string.ds_ok, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.ds_fail, Toast.LENGTH_LONG).show();
                }
            });
        }, "DsValidate").start();
    }

    /** גיבוי/שחזור מוצפן — בלי שרת ובלי הרשאת אחסון. */
    private void showBackupDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etPass = new EditText(this);
        etPass.setHint(R.string.backup_pass);
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etPass);

        final EditText etRestore = new EditText(this);
        etRestore.setHint(R.string.backup_paste);
        etRestore.setMinLines(2);
        box.addView(etRestore);

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.backup_title)
            .setView(box)
            .setPositiveButton(R.string.backup_export, (d, w) -> {
                String pass = etPass.getText().toString();
                if (pass.isEmpty()) { toast(R.string.backup_need_pass); return; }
                try {
                    String data = com.magen.family.backup.BackupManager.export(this, pass);
                    shareText(data);
                    toast(R.string.backup_done);
                } catch (Exception e) {
                    Toast.makeText(this, "שגיאה: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNeutralButton(R.string.backup_restore, (d, w) -> {
                String pass = etPass.getText().toString();
                String data = etRestore.getText().toString().trim();
                if (pass.isEmpty()) { toast(R.string.backup_need_pass); return; }
                boolean ok = com.magen.family.backup.BackupManager.restore(this, data, pass);
                toast(ok ? R.string.backup_restored : R.string.backup_bad);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showUpdateDialog() {
        final EditText etUrl = new EditText(this);
        etUrl.setHint(R.string.update_url);
        etUrl.setText(com.magen.family.service.UpdateChecker.getManifestUrl(this));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(etUrl);
        TextView hint = new TextView(this);
        hint.setText(R.string.update_hint);
        hint.setTextSize(12);
        hint.setTextColor(0xFF9E9E9E);
        box.addView(hint);

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setView(box)
            .setPositiveButton(R.string.save, (d, w) -> {
                com.magen.family.service.UpdateChecker.setManifestUrl(this,
                    etUrl.getText().toString());
                Toast.makeText(this, "✓", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void shareText(String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, getString(R.string.backup_share)));
        } catch (Exception ignored) {}
    }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** בורר שפה — מיושם מיד ע"י יצירת ה-Activity מחדש. */
    private void showLanguageDialog() {
        final String[] labels = { "עברית", "English" };
        final String[] codes  = { com.magen.family.i18n.LocaleManager.HE,
                                  com.magen.family.i18n.LocaleManager.EN };
        int current = com.magen.family.i18n.LocaleManager.isHebrew(this) ? 0 : 1;

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.main_language)
            .setSingleChoiceItems(labels, current, (d, which) -> {
                com.magen.family.i18n.LocaleManager.setLanguage(this, codes[which]);
                d.dismiss();
                recreate();   // מרענן את המסך בשפה החדשה
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /** רשימת ההיתר — נחוצה כי ה-Bloom filter חוסם באחוז קטן דומיינים תמימים. */
    private void showAllowListDialog() {
        java.util.List<String> hosts =
            new ArrayList<>(HostAllowList.get(this));
        Collections.sort(hosts);

        final EditText input = new EditText(this);
        input.setHint("example.com");

        CharSequence[] items = hosts.isEmpty()
            ? new CharSequence[]{ "(אין אתרים מותרים)" }
            : hosts.toArray(new CharSequence[0]);

        new android.app.AlertDialog.Builder(this)
            .setTitle("אתרים שנפתחו ידנית")
            .setItems(items, (d, which) -> {
                if (hosts.isEmpty()) return;
                String host = hosts.get(which);
                new android.app.AlertDialog.Builder(this)
                    .setMessage("להסיר את " + host + " מרשימת המותרים?")
                    .setPositiveButton("הסר", (d2, w2) -> {
                        HostAllowList.remove(this, host);
                        Toast.makeText(this, "הוסר", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("ביטול", null)
                    .show();
            })
            .setView(input)
            .setPositiveButton("הוסף", (d, w) -> {
                String host = input.getText().toString().trim();
                if (!host.isEmpty()) {
                    HostAllowList.allow(this, host);
                    Toast.makeText(this, "✓ " + host + " מותר", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("סגור", null)
            .show();
    }

    private void requestSmsPermissionIfNeeded() {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{ android.Manifest.permission.SEND_SMS }, 1002);
            }
        } catch (Exception ignored) {}
    }

    private void restartVpn() {
        try {
            stopService(new Intent(this, MagenVpnService.class));
            startVpn();
        } catch (Exception ignored) {}
    }

    private void updateAdvancedSubtitle() {
        TextView sub = findViewById(R.id.tv_advanced_sub);
        if (sub == null) return;
        sub.setText(VpnPolicy.fullTunnel() ? "סינון מלא פעיל" : "סינון DNS בלבד");
    }

    // ===== דיאלוג נעילת צינון =====
    private void showLockoutDialog() {
        boolean enabled = MagenConfig.isLockoutEnabled(this);
        int curMin = MagenConfig.getLockoutMinutes(this);
        final int[] options = {5, 10, 15, 30, 60};
        final String[] labels = {"5 דקות", "10 דקות", "15 דקות", "30 דקות", "שעה"};

        int sel = 1;
        for (int i = 0; i < options.length; i++) if (options[i] == curMin) sel = i;
        final int[] chosen = {sel};

        new android.app.AlertDialog.Builder(this)
            .setTitle("נעילת צינון בעת זיהוי תוכן")
            .setSingleChoiceItems(labels, sel, (d, w) -> chosen[0] = w)
            .setPositiveButton(enabled ? "עדכן" : "הפעל", (d, w) -> {
                MagenConfig.setLockoutEnabled(this, true);
                MagenConfig.setLockoutMinutes(this, options[chosen[0]]);
                android.widget.Toast.makeText(this,
                    "נעילת צינון: " + labels[chosen[0]], android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("בטל נעילה", (d, w) -> {
                MagenConfig.setLockoutEnabled(this, false);
                android.widget.Toast.makeText(this,
                    "נעילת צינון בוטלה", android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("סגור", null)
            .show();
    }

    static class AppItem {
        String packageName, name;
        Drawable icon;
        boolean blocked;
    }

    // ===== Adapter מקצועי =====
    class AppListAdapter extends BaseAdapter {
        private final List<AppItem> items;
        AppListAdapter(List<AppItem> i) { items = i; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null)
                cv = LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_app, parent, false);

            AppItem item = items.get(pos);
            ImageView icon  = cv.findViewById(R.id.app_icon);
            TextView  name  = cv.findViewById(R.id.app_name);
            TextView  badge = cv.findViewById(R.id.app_badge);
            android.widget.CompoundButton sw = cv.findViewById(R.id.app_check);

            icon.setImageDrawable(item.icon);
            name.setText(item.name);
            sw.setChecked(item.blocked);
            badge.setVisibility(item.blocked ? View.VISIBLE : View.GONE);

            // רקע הכרטיס הפנימי: חסום = אדום בהיר, רגיל = לבן
            View card = cv.findViewById(R.id.app_card);
            if (card != null) {
                card.setBackgroundResource(item.blocked
                    ? R.drawable.bg_card_blocked
                    : R.drawable.bg_card);
            }

            cv.setOnClickListener(v -> {
                pendingBlockPackage = item.packageName;
                pendingBlockState   = !item.blocked;
                askPin(REQ_PIN_APP_BLOCK);
            });

            return cv;
        }
    }
}
