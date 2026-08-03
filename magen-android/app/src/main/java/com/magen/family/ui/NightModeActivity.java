package com.magen.family.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.magen.family.MagenApp;
import com.magen.family.R;
import com.magen.family.service.NightModeService;

public class NightModeActivity extends AppCompatActivity {

    private Switch swNight;
    private NumberPicker pickerStart, pickerEnd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_night_mode);

        swNight     = findViewById(R.id.sw_night);
        pickerStart = findViewById(R.id.picker_start);
        pickerEnd   = findViewById(R.id.picker_end);

        SharedPreferences prefs = MagenApp.getInstance().getPrefs();

        // הגדרת Pickers
        String[] hours = new String[24];
        for (int i = 0; i < 24; i++) hours[i] = String.format("%02d:00", i);

        pickerStart.setMinValue(0);
        pickerStart.setMaxValue(23);
        pickerStart.setDisplayedValues(hours);
        pickerStart.setValue(prefs.getInt("night_start_hour", 22));

        pickerEnd.setMinValue(0);
        pickerEnd.setMaxValue(23);
        pickerEnd.setDisplayedValues(hours);
        pickerEnd.setValue(prefs.getInt("night_end_hour", 7));

        swNight.setChecked(prefs.getBoolean("night_mode_enabled", false));

        swNight.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("night_mode_enabled", checked).apply();
            if (checked) NightModeService.schedule(this);
            Toast.makeText(this,
                checked ? "🌙 מצב לילה פעיל" : "☀️ מצב לילה כבוי",
                Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            prefs.edit()
                .putInt("night_start_hour", pickerStart.getValue())
                .putInt("night_end_hour", pickerEnd.getValue())
                .apply();
            NightModeService.schedule(this);
            Toast.makeText(this, "✓ נשמר!", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
