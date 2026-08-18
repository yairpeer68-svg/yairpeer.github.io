package com.magen.family.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.magen.family.MagenApp;
import com.magen.family.R;
import com.magen.family.service.ScreenTimeService;

public class ScreenTimeActivity extends BaseActivity {

    private Switch swEnabled;
    private NumberPicker pickerHours, pickerMinutes;
    private TextView tvUsedToday;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_time);

        swEnabled    = findViewById(R.id.sw_screen_time);
        pickerHours  = findViewById(R.id.picker_hours);
        pickerMinutes = findViewById(R.id.picker_minutes);
        tvUsedToday  = findViewById(R.id.tv_used_today);

        SharedPreferences prefs = MagenApp.getInstance().getPrefs();

        // Pickers
        pickerHours.setMinValue(0);
        pickerHours.setMaxValue(12);

        String[] mins = {"00", "15", "30", "45"};
        pickerMinutes.setMinValue(0);
        pickerMinutes.setMaxValue(3);
        pickerMinutes.setDisplayedValues(mins);

        int maxMin = prefs.getInt("screen_time_max_minutes", 120);
        pickerHours.setValue(maxMin / 60);
        pickerMinutes.setValue((maxMin % 60) / 15);

        swEnabled.setChecked(prefs.getBoolean("screen_time_enabled", false));

        // זמן שימוש היום
        long usedToday = prefs.getLong("screen_time_today_minutes", 0);
        tvUsedToday.setText("שימוש היום: " + usedToday / 60 + "ש׳ " + usedToday % 60 + "ד׳");

        // הפעל Usage Stats אם צריך
        if (!hasUsagePermission()) {
            Toast.makeText(this,
                "יש לאשר הרשאת סטטיסטיקות שימוש",
                Toast.LENGTH_LONG).show();
            com.magen.family.service.MagenGuard.grantMaintenance(
                this, com.magen.family.service.MagenGuard.SCOPE_USAGE);
            com.magen.family.util.SafeLaunch.openAction(this,
                Settings.ACTION_USAGE_ACCESS_SETTINGS);
        }

        swEnabled.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("screen_time_enabled", checked).apply();
            if (checked) startService(new Intent(this, ScreenTimeService.class));
        });

        findViewById(R.id.btn_save_screen).setOnClickListener(v -> {
            int hours = pickerHours.getValue();
            int minutesVal = Integer.parseInt(
                pickerMinutes.getDisplayedValues()[pickerMinutes.getValue()]);
            int totalMinutes = hours * 60 + minutesVal;

            prefs.edit().putInt("screen_time_max_minutes", totalMinutes).apply();
            startService(new Intent(this, ScreenTimeService.class));
            Toast.makeText(this,
                "✓ מוגדר ל-" + hours + "ש׳ " + minutesVal + "ד׳",
                Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private boolean hasUsagePermission() {
        try {
            android.app.AppOpsManager aom =
                (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }
}
