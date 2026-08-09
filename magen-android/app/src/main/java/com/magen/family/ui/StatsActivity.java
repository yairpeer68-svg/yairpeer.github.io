package com.magen.family.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.magen.family.MagenApp;
import com.magen.family.R;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public class StatsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        loadStats();
    }

    private void loadStats() {
        SharedPreferences prefs = MagenApp.getInstance().getPrefs();

        int totalBlocked = prefs.getInt(MagenApp.KEY_BLOCKED_COUNT, 0);
        int todayBlocked  = prefs.getInt("blocked_today", 0);
        int weekBlocked   = prefs.getInt("blocked_week", 0);
        long lastAttempt  = prefs.getLong("last_block_time", 0);
        int vpnAttempts   = prefs.getInt("vpn_bypass_attempts", 0);
        int settingsAttempts = prefs.getInt("settings_attempts", 0);

        setText(R.id.tv_total,    String.valueOf(totalBlocked));
        setText(R.id.tv_today,    String.valueOf(todayBlocked));
        setText(R.id.tv_week,     String.valueOf(weekBlocked));
        setText(R.id.tv_vpn,      String.valueOf(vpnAttempts));
        setText(R.id.tv_settings, String.valueOf(settingsAttempts));

        if (lastAttempt > 0) {
            String time = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                .format(new Date(lastAttempt));
            setText(R.id.tv_last, time);
        } else {
            setText(R.id.tv_last, "אין ניסיונות");
        }
    }

    private void setText(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }
}
