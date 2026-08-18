package com.magen.family.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.magen.family.MagenApp;
import com.magen.family.R;

public class WelcomeActivity extends BaseActivity {

    private static final int MAIN_REQUEST_CODE = 999;
    private static final int PIN_REQUEST_CODE  = 998;

    private TextView tvSpeech;
    private EditText etName;
    private Button btnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // דוח קריסה מהפעם הקודמת שלא הספיק להיות מוצג — מציגים עכשיו.
        String pending = com.magen.family.CrashLogger.consumePending(this);
        if (pending != null) {
            Intent c = new Intent(this, CrashActivity.class);
            c.putExtra(CrashActivity.EXTRA_REPORT, pending);
            try { startActivity(c); } catch (Exception ignored) {}
        }

        // חסימת צילומי מסך
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );

        // בקשת הרשאת התראות (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        // מעבר אוטומטי מותר רק אחרי שגם הוגדר PIN. שם שנשמר לפני
        // שהגדרת ה-PIN הושלמה אינו נחשב setup תקין (למשל BACK באמצע).
        String savedName = MagenApp.getInstance().getPrefs()
            .getString("parent_name", "");
        if (!savedName.isEmpty() && MagenApp.getInstance().hasPin()) {
            goToMain();
            return;
        }

        setContentView(R.layout.activity_welcome);

        tvSpeech = findViewById(R.id.tv_welcome_title);
        etName   = findViewById(R.id.et_name);
        btnNext  = findViewById(R.id.btn_next);

        boolean clearDataTamper = MagenApp.getInstance().getPrefs()
            .getBoolean(MagenApp.KEY_CLEAR_DATA_TAMPER, false);
        if (clearDataTamper) {
            tvSpeech.setText("⚠️ זוהתה מחיקת נתוני האפליקציה. יש להגדיר מחדש קוד והרשאות הגנה.");
        } else {
            tvSpeech.setText("מה השם שלך?");
        }
        if (!savedName.isEmpty()) etName.setText(savedName);

        btnNext.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError("נא להזין שם");
                return;
            }

            // שמור שם
            MagenApp.getInstance().getPrefs()
                .edit().putString("parent_name", name).apply();

            // תגובה אנושית
            tvSpeech.setText("נעים מאוד " + name + "! 🎉");
            etName.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);

            // השהיה קלה ואז הגדרת PIN
            new Handler().postDelayed(() -> {
                Intent pinIntent = new Intent(this, PinActivity.class);
                pinIntent.putExtra("mode", "change");
                pinIntent.putExtra("first_time", true);
                startActivityForResult(pinIntent, PIN_REQUEST_CODE);
            }, 1200);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == PIN_REQUEST_CODE) {
            if (res != RESULT_OK || !MagenApp.getInstance().hasPin()) {
                // אל תאפשר setup חלקי להפוך לנתיב עוקף בפתיחה הבאה.
                if (tvSpeech != null) tvSpeech.setText("יש להשלים הגדרת קוד לפני שממשיכים");
                if (etName != null) etName.setVisibility(View.VISIBLE);
                if (btnNext != null) btnNext.setVisibility(View.VISIBLE);
                return;
            }
            // PIN חדש נוצר בהצלחה. Clear-Data tamper כבר לא במצב "ללא קוד",
            // אך onboarding נשאר לא-מושלם כדי שכל שכבות ההגנה יוגדרו מחדש.
            MagenApp.getInstance().getPrefs().edit()
                .putBoolean(MagenApp.KEY_CLEAR_DATA_TAMPER, false)
                .apply();

            // אחרי הגדרת קוד הברית — אם המדריך עוד לא הושלם, עבור אליו קודם
            boolean onboardingDone = MagenApp.getInstance().getPrefs()
                .getBoolean("onboarding_done", false);
            if (!onboardingDone) {
                startActivity(new Intent(this, OnboardingActivity.class));
                finish();
            } else {
                goToMain();
            }

        } else if (req == MAIN_REQUEST_CODE) {
            // חזרה מ-MainActivity — אפס תצוגה
            if (tvSpeech != null) tvSpeech.setText("מה השם שלך?");
            if (etName != null) {
                etName.setVisibility(View.VISIBLE);
                etName.setText("");
            }
            if (btnNext != null) btnNext.setVisibility(View.VISIBLE);
        }
    }

    private void goToMain() {
        // startActivityForResult — WelcomeActivity נשאר חי ב-stack!
        startActivityForResult(
            new Intent(this, MainActivity.class),
            MAIN_REQUEST_CODE
        );
    }
}
