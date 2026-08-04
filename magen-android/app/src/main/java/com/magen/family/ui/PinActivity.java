package com.magen.family.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.magen.family.MagenApp;
import com.magen.family.R;
import com.magen.family.security.PasswordHasher;

/**
 * PinActivity — מסך הזנת קוד גישה.
 *
 * שיפורים מהגרסה הקודמת:
 *   • אין EMERGENCY_PIN קבוע בקוד.  מייצרים קוד חירום רנדומלי בן 6 ספרות
 *     בהגדרה הראשונה, לשמור אותו.
 *   • PIN נשמר עם PBKDF2 + salt (לא SHA-256 חשוף).
 *   • הגנה מצילומי מסך (FLAG_SECURE).
 *   • Lockout פרוגרסיבי: 5 ניסיונות = 5 דק, 10 ניסיונות = שעה.
 */
public class PinActivity extends BaseActivity {

    private static final int PIN_LENGTH    = 4;
    private static final int EMERGENCY_LEN = 6;
    private static final int MAX_ATTEMPTS  = 5;
    private static final long LOCKOUT_MS   = 5 * 60 * 1000L;
    private static final long LONG_LOCKOUT_MS = 60 * 60 * 1000L;

    /** חלון החסד שבו אפשר להמשיך לקוד חירום לפני שנספר ניסיון כושל. */
    private static final long FAILURE_GRACE_MS = 1500L;

    private String mode = "verify"; // verify / change
    private boolean fromKillSwitch = false;
    private boolean firstTime = false;
    private boolean authenticated = false;
    private String enteredPin = "";
    private String firstPin = "";

    private final Handler failureHandler = new Handler();
    private Runnable pendingFailure;

    private TextView tvTitle, tvSubtitle;
    private View[] dots = new View[PIN_LENGTH];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // הגנה מצילומי מסך — אסור לראות PIN במסך האחרונים
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                             WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_pin);

        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "verify";
        firstTime = getIntent().getBooleanExtra("first_time", false);
        fromKillSwitch = getIntent().getBooleanExtra("killswitch", false);

        tvTitle    = findViewById(R.id.tv_pin_title);
        tvSubtitle = findViewById(R.id.tv_pin_subtitle);
        dots[0] = findViewById(R.id.dot1);
        dots[1] = findViewById(R.id.dot2);
        dots[2] = findViewById(R.id.dot3);
        dots[3] = findViewById(R.id.dot4);

        String name = MagenApp.getInstance().getPrefs().getString("parent_name", "");

        if (mode.equals("change") && firstTime) {
            tvTitle.setText(R.string.covenant_code_create);
            tvSubtitle.setText(getString(R.string.covenant_code_hint));
        } else if (mode.equals("change")) {
            tvTitle.setText(R.string.covenant_code);
            tvSubtitle.setText(getString(R.string.covenant_code_enter));
        } else {
            // מצב אימות — פשוט "הזן קוד", בלי מסגור הורה ובלי ברכת שם
            tvTitle.setText(R.string.covenant_code_enter);
            tvSubtitle.setText("");
        }

        setupButtons();

        if (mode.equals("verify") && BiometricHelper.isAvailable(this)) {
            showFingerprintButton();
        }
    }

    private void setupButtons() {
        int[] numIds = {
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        };
        for (int i = 0; i < numIds.length; i++) {
            final int num = i;
            View btn = findViewById(numIds[i]);
            if (btn != null) btn.setOnClickListener(v -> addDigit(String.valueOf(num)));
        }
        View del = findViewById(R.id.btn_del);
        if (del != null) del.setOnClickListener(v -> deleteDigit());
    }

    private void showFingerprintButton() {
        View fpBtn = findViewById(R.id.btn_fingerprint);
        if (fpBtn == null) return;
        fpBtn.setVisibility(View.VISIBLE);
        fpBtn.setOnClickListener(v ->
            BiometricHelper.authenticate(this, new BiometricHelper.BiometricCallback() {
                @Override public void onSuccess() { setResult(RESULT_OK); finish(); }
                @Override public void onFailed() {
                    tvSubtitle.setText("טביעת אצבע לא הוכרה");
                    tvSubtitle.setTextColor(0xFFE53935);
                    new Handler().postDelayed(() -> {
                        tvSubtitle.setText("מה הקוד שלך?");
                        tvSubtitle.setTextColor(0xFF9E9E9E);
                    }, 1500);
                }
                @Override public void onNotAvailable() {}
            }));
    }

    private void addDigit(String digit) {
        if (enteredPin.length() >= EMERGENCY_LEN) return;

        // כל הקלדה מבטלת ספירת כישלון שממתינה
        cancelPendingFailure();

        enteredPin += digit;
        updateDots();

        if (enteredPin.length() == PIN_LENGTH) {
            new Handler().postDelayed(() -> {
                if (mode.equals("verify")) {
                    if (tryVerifyRegularPin()) return;   // הצליח — הפעילות נסגרת
                    // נכשל. אולי המשתמש ממשיך לקוד חירום בן 6 ספרות, ולכן לא
                    // סופרים כישלון מיד — אבל *כן* סופרים אם הוא לא ממשיך.
                    // בלי זה לא הייתה שום הגבלת קצב על ניחוש PIN בן 4 ספרות.
                    schedulePendingFailure();
                } else {
                    processPin();
                }
            }, 150);
        } else if (enteredPin.length() == EMERGENCY_LEN && mode.equals("verify")) {
            new Handler().postDelayed(this::tryVerifyEmergencyPin, 150);
        }
    }

    /**
     * סופר ניסיון כושל אם המשתמש לא המשיך להקליד לקוד חירום.
     * זה מה שמחזיר לחיים את מנגנון הנעילה: קודם tryVerifyRegularPin() החזיר
     * false בלי לספור כלום, ולכן היה אפשר לנחות 10,000 צירופים בלי הגבלה.
     */
    private void schedulePendingFailure() {
        cancelPendingFailure();
        pendingFailure = () -> {
            pendingFailure = null;
            countFailedAttempt();
        };
        failureHandler.postDelayed(pendingFailure, FAILURE_GRACE_MS);
    }

    private void cancelPendingFailure() {
        if (pendingFailure != null) {
            failureHandler.removeCallbacks(pendingFailure);
            pendingFailure = null;
        }
    }

    private void deleteDigit() {
        cancelPendingFailure();
        if (enteredPin.length() > 0) {
            enteredPin = enteredPin.substring(0, enteredPin.length() - 1);
            updateDots();
        }
    }

    private void updateDots() {
        for (int i = 0; i < PIN_LENGTH; i++) {
            dots[i].setBackgroundResource(i < enteredPin.length()
                ? R.drawable.bg_pin_dot
                : android.R.color.transparent);
        }
    }

    private void processPin() {
        if (mode.equals("change")) {
            changePin();
        }
    }

    /**
     * אימות PIN בן 4 ספרות.  מחזיר true אם הצליח (וסוגר את ה-Activity).
     */
    private boolean tryVerifyRegularPin() {
        if (isLockedOut()) return false;

        String savedHash = MagenApp.getInstance().getPrefs()
            .getString(MagenApp.KEY_PIN, "");

        if (PasswordHasher.verify(enteredPin, savedHash)) {
            authenticated = true;
            resetAttempts();
            clearKillSwitchIfNeeded();
            setResult(RESULT_OK);
            finish();
            return true;
        }
        // אל תספור ניסיון כושל עדיין — אולי המשתמש רוצה להמשיך ל-emergency PIN
        return false;
    }

    /**
     * אימות emergency PIN בן 6 ספרות (אם המשתמש המשיך להקליד).
     */
    private void tryVerifyEmergencyPin() {
        if (isLockedOut()) return;

        String emergencyHash = MagenApp.getInstance().getPrefs()
            .getString("emergency_pin_hash", "");

        if (!emergencyHash.isEmpty() && PasswordHasher.verify(enteredPin, emergencyHash)) {
            authenticated = true;
            Toast.makeText(this, "🔓 קוד חירום אומת", Toast.LENGTH_SHORT).show();
            MagenApp.getInstance().getPrefs().edit()
                .putBoolean("emergency_mode", true)
                .putLong("emergency_mode_until",
                    System.currentTimeMillis() + 15 * 60 * 1000L) // חירום ל-15 דק
                .apply();
            resetAttempts();
            clearKillSwitchIfNeeded();
            setResult(RESULT_OK);
            finish();
            return;
        }

        // PIN שגוי לחלוטין — ספור ניסיון
        countFailedAttempt();
    }

    private boolean isLockedOut() {
        long lockUntil = MagenApp.getInstance().getPrefs().getLong("pin_lock_until", 0);
        if (System.currentTimeMillis() < lockUntil) {
            long remaining = (lockUntil - System.currentTimeMillis()) / 1000 / 60;
            tvSubtitle.setText("נעול " + Math.max(1, remaining) + " דק׳ ⏳");
            tvSubtitle.setTextColor(0xFFE53935);
            enteredPin = "";
            updateDots();
            return true;
        }
        return false;
    }

    private void countFailedAttempt() {
        int attempts = MagenApp.getInstance().getPrefs().getInt("pin_attempts", 0) + 1;
        int totalFailed = MagenApp.getInstance().getPrefs().getInt("pin_total_failed", 0) + 1;

        // Lockout פרוגרסיבי
        long lockoutMs = totalFailed >= 10 ? LONG_LOCKOUT_MS : LOCKOUT_MS;

        if (attempts >= MAX_ATTEMPTS) {
            long lockUntil = System.currentTimeMillis() + lockoutMs;
            MagenApp.getInstance().getPrefs().edit()
                .putLong("pin_lock_until", lockUntil)
                .putInt("pin_attempts", 0)
                .putInt("pin_total_failed", totalFailed)
                .apply();
            long minutes = lockoutMs / 60_000;
            tvSubtitle.setText("יותר מדי ניסיונות! נעול " + minutes + " דק׳ 🔒");
            tvSubtitle.setTextColor(0xFFE53935);

            // התראה לשותף אחריות
            com.magen.family.service.NotificationHelper.notifyPartnerUrgent(
                this, "ניסיונות PIN שגויים — נעילה ל-" + minutes + " דק׳");
        } else {
            MagenApp.getInstance().getPrefs().edit()
                .putInt("pin_attempts", attempts)
                .putInt("pin_total_failed", totalFailed)
                .apply();
            int left = MAX_ATTEMPTS - attempts;
            tvSubtitle.setText("קוד שגוי — נשארו " + left + " ניסיונות");
            tvSubtitle.setTextColor(0xFFE53935);
        }

        enteredPin = "";
        updateDots();
        new Handler().postDelayed(() -> {
            tvSubtitle.setText("מה הקוד שלך?");
            tvSubtitle.setTextColor(0xFF9E9E9E);
        }, 2000);
    }

    private void resetAttempts() {
        MagenApp.getInstance().getPrefs().edit()
            .putInt("pin_attempts", 0)
            .putLong("pin_lock_until", 0)
            .apply();
    }

    private void changePin() {
        if (firstPin.isEmpty()) {
            firstPin = enteredPin;
            enteredPin = "";
            updateDots();
            tvSubtitle.setText("הזן שוב לאימות");
        } else {
            if (firstPin.equals(enteredPin)) {
                // שמור PIN חדש (PBKDF2)
                String hash = PasswordHasher.hash(enteredPin);
                MagenApp.getInstance().getPrefs().edit()
                    .putString(MagenApp.KEY_PIN, hash)
                    .apply();

                // סמן שורד "נקה נתונים" — מאפשר לזהות איפוס של האפליקציה
                com.magen.family.security.InstallMarker.write(this);

                // הגדרה ראשונה? יצור גם emergency PIN רנדומלי
                if (firstTime) {
                    String emergency = PasswordHasher.generateEmergencyPin();
                    String emergencyHash = PasswordHasher.hash(emergency);
                    MagenApp.getInstance().getPrefs().edit()
                        .putString("emergency_pin_hash", emergencyHash)
                        .apply();
                    showEmergencyCodeOnce(emergency);
                    return;
                }

                Toast.makeText(this, "✓ הקוד עודכן", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            } else {
                tvSubtitle.setText("הקודים לא תואמים — נסה שוב");
                tvSubtitle.setTextColor(0xFFE53935);
                firstPin = "";
                enteredPin = "";
                updateDots();
                new Handler().postDelayed(() -> {
                    tvSubtitle.setText("הזן קוד חדש");
                    tvSubtitle.setTextColor(0xFF9E9E9E);
                }, 1200);
            }
        }
    }

    /**
     * הצג את קוד החירום פעם אחת — המשתמש צריך לצלם/לשמור אותו.
     */
    private void showEmergencyCodeOnce(String code) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔑 קוד חירום שלך")
            .setMessage("שמור את הקוד הזה במקום בטוח:\n\n"
                + code + "\n\n"
                + "אם תשכח את ה-PIN הרגיל, הקלד את הקוד הזה במקום (6 ספרות).\n"
                + "הקוד הזה לא יוצג שוב.")
            .setCancelable(false)
            .setPositiveButton("שמרתי", (d, w) -> {
                setResult(RESULT_OK);
                finish();
            })
            .show();
    }

    @Override
    public void onBackPressed() {
        cancelPendingFailure();
        // אם הגיע מ-KillSwitch ולא אומת — החזר את מסך החסימה
        if (fromKillSwitch && !authenticated) {
            rearmKillSwitch();
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    /** מנקה את נעילת ה-KillSwitch אחרי אימות מוצלח. */
    private void clearKillSwitchIfNeeded() {
        if (fromKillSwitch) {
            getSharedPreferences("magen_killswitch", MODE_PRIVATE)
                .edit()
                .remove("unlock_at")
                .remove("require_pin")
                .apply();
        }
    }

    /**
     * מחזיר את ה-KillSwitch אם המשתמש יצא בלי לאמת.
     *
     * הבאג שתוקן: קודם הבדיקה הייתה רק unlockAt > now. במצב "נעול עד PIN"
     * (שזה בדיוק המצב שנוצר בניסיון לבטל את מנהל המכשיר) הערך היה 0, ולכן
     * הנעילה *לא* הוחזרה — לחיצה על "פתיחה עם קוד" ואז BACK פתחה את המכשיר
     * בלי שום קוד. עכשיו גם דגל require_pin מחזיר את הנעילה.
     */
    private void rearmKillSwitch() {
        try {
            android.content.SharedPreferences ks =
                getSharedPreferences("magen_killswitch", MODE_PRIVATE);
            long unlockAt   = ks.getLong("unlock_at", 0);
            boolean requirePin = ks.getBoolean("require_pin", false);

            if (requirePin || unlockAt > System.currentTimeMillis()) {
                android.content.Intent i = new android.content.Intent(this,
                    com.magen.family.service.MagenKillSwitch.class);
                i.putExtra("require_pin", requirePin);
                startService(i);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        // אם יצא מהמסך בלי לאמת (למשל HOME) — החזר את החסימה
        if (fromKillSwitch && !authenticated && !isFinishing()) {
            rearmKillSwitch();
        }
    }

    @Override
    protected void onDestroy() {
        cancelPendingFailure();
        super.onDestroy();
    }
}
