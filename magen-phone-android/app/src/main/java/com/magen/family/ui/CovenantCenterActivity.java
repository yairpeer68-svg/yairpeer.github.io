package com.magen.family.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.magen.family.R;
import com.magen.family.covenant.CovenantJournal;
import com.magen.family.covenant.StreakManager;
import com.magen.family.service.NotificationHelper;

import java.util.List;

/**
 * CovenantCenterActivity — לב מודל הברית: רצף נקי, כפתור מצוקה, צ׳ק-אין,
 * יומן ונוסח הברית. הכל מקומי, בלי שרת.
 *
 * המסך נבנה בקוד כדי לשמור על גמישות, ומרוענן ב-onResume.
 */
public class CovenantCenterActivity extends BaseActivity {

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StreakManager.ensureStarted(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_light));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
        checkMilestone();
    }

    /** בדיקת אבן דרך חדשה ברצף — מברכים את המשתמש ומדווחים לשרת. */
    private void checkMilestone() {
        int days = StreakManager.currentDays(this);
        int m = com.magen.family.stats.BlockStats.newStreakMilestone(this, days);
        if (m > 0) {
            new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.ach_title)
                .setMessage(getString(R.string.ach_body, m))
                .setPositiveButton(R.string.ok, null)
                .show();
            NotificationHelper.notifyDigest(this, "🏆 אבן דרך: " + m + " ימים נקיים ברצף.");
        }
    }

    private void build() {
        root.removeAllViews();

        // ===== כרטיס הרצף =====
        LinearLayout streakCard = card();
        streakCard.setGravity(Gravity.CENTER);

        int days = StreakManager.currentDays(this);
        TextView big = new TextView(this);
        big.setText(days > 0 ? getString(R.string.cov_streak_days, days)
                             : getString(R.string.cov_streak_zero));
        big.setTextSize(28);
        big.setTextColor(ContextCompat.getColor(this, R.color.accent));
        big.setGravity(Gravity.CENTER);
        streakCard.addView(big);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.cov_longest, StreakManager.longestDays(this))
            + "   ·   " + getString(R.string.cov_total_slips, StreakManager.totalSlips(this)));
        sub.setTextSize(13);
        sub.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        sub.setGravity(Gravity.CENTER);
        streakCard.addView(sub);
        root.addView(streakCard);

        // ===== כפתור מצוקה =====
        Button panic = bigButton(getString(R.string.cov_panic), R.color.danger);
        panic.setOnClickListener(v -> showPanic());
        root.addView(panic);

        // ===== צ׳ק-אין =====
        LinearLayout checkinCard = card();
        TextView ciTitle = sectionTitle(getString(R.string.cov_checkin));
        checkinCard.addView(ciTitle);

        if (CovenantJournal.didCheckInToday(this)) {
            TextView done = new TextView(this);
            done.setText(R.string.cov_checkin_done);
            done.setTextColor(ContextCompat.getColor(this, R.color.success));
            checkinCard.addView(done);
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(moodButton(R.string.cov_checkin_strong, "חזק"));
            row.addView(moodButton(R.string.cov_checkin_ok, "בסדר"));
            row.addView(moodButton(R.string.cov_checkin_struggle, "מתקשה"));
            checkinCard.addView(row);
        }
        root.addView(checkinCard);

        // ===== נוסח הברית =====
        LinearLayout pledgeCard = card();
        pledgeCard.addView(sectionTitle(getString(R.string.cov_pledge)));

        TextView pledgeText = new TextView(this);
        pledgeText.setText(CovenantJournal.hasPledge(this)
            ? CovenantJournal.getPledge(this)
            : getString(R.string.cov_pledge_default));
        pledgeText.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        pledgeText.setPadding(0, dp(4), 0, dp(6));
        pledgeCard.addView(pledgeText);

        if (CovenantJournal.hasPledge(this)) {
            TextView signed = new TextView(this);
            signed.setText(getString(R.string.cov_pledge_signed,
                CovenantJournal.formatDate(CovenantJournal.getPledgeDate(this))));
            signed.setTextSize(12);
            signed.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            pledgeCard.addView(signed);
        }

        Button signBtn = new Button(this);
        signBtn.setAllCaps(false);
        signBtn.setText(CovenantJournal.hasPledge(this)
            ? R.string.cov_pledge_edit : R.string.cov_pledge_sign);
        signBtn.setOnClickListener(v -> showPledgeEditor());
        pledgeCard.addView(signBtn);
        root.addView(pledgeCard);

        // ===== יומן =====
        LinearLayout journalCard = card();
        journalCard.addView(sectionTitle(getString(R.string.cov_journal)));

        Button addEntry = new Button(this);
        addEntry.setAllCaps(false);
        addEntry.setText(R.string.cov_journal_add);
        addEntry.setOnClickListener(v -> showJournalEditor());
        journalCard.addView(addEntry);

        List<CovenantJournal.Entry> entries = CovenantJournal.getEntries(this);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.cov_journal_empty);
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            journalCard.addView(empty);
        } else {
            int shown = 0;
            for (CovenantJournal.Entry e : entries) {
                if (shown++ >= 10) break;   // רק האחרונות במסך
                TextView tv = new TextView(this);
                tv.setText(CovenantJournal.formatDate(e.time) + "  —  " + e.text);
                tv.setTextSize(13);
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tv.setPadding(0, dp(6), 0, 0);
                journalCard.addView(tv);
            }
        }
        root.addView(journalCard);

        // ===== דיווח על החלקה =====
        Button slip = new Button(this);
        slip.setAllCaps(false);
        slip.setText(R.string.cov_slip);
        slip.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        slip.setBackgroundColor(0x00000000);
        slip.setOnClickListener(v -> confirmSlip());
        root.addView(slip);
    }

    // ---------------- דיאלוגים ----------------

    private void showPanic() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);

        TextView body = new TextView(this);
        body.setText(R.string.cov_panic_body);
        body.setTextSize(16);
        box.addView(body);

        TextView breathe = new TextView(this);
        breathe.setText(R.string.cov_panic_breathe);
        breathe.setTextColor(ContextCompat.getColor(this, R.color.accent));
        breathe.setPadding(0, dp(14), 0, 0);
        box.addView(breathe);

        // התראה לשרת שנעזר בכפתור — חלק מהשקיפות
        try {
            NotificationHelper.notifyDigest(this,
                "🌿 נעזרתי בכפתור המצוקה. אני מתמודד — הכל בסדר.");
        } catch (Exception ignored) {}

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.cov_panic_title)
            .setView(box)
            .setPositiveButton(R.string.cov_panic_passed, (d, w) ->
                CovenantJournal.addEntry(this, "השתמשתי בכפתור המצוקה — עבר בשלום."))
            .show();
    }


    private void showPledgeEditor() {
        final EditText input = new EditText(this);
        input.setText(CovenantJournal.hasPledge(this)
            ? CovenantJournal.getPledge(this)
            : getString(R.string.cov_pledge_default));
        input.setMinLines(3);

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.cov_pledge)
            .setView(input)
            .setPositiveButton(R.string.cov_pledge_sign, (d, w) -> {
                CovenantJournal.signPledge(this, input.getText().toString().trim());
                build();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showJournalEditor() {
        final EditText input = new EditText(this);
        input.setMinLines(2);
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.cov_journal_add)
            .setView(input)
            .setPositiveButton(R.string.save, (d, w) -> {
                CovenantJournal.addEntry(this, input.getText().toString());
                build();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void confirmSlip() {
        new android.app.AlertDialog.Builder(this)
            .setMessage(R.string.cov_slip_confirm)
            .setPositiveButton(R.string.ok, (d, w) -> {
                StreakManager.selfReportSlip(this);
                build();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // ---------------- עזרי UI ----------------

    private Button moodButton(int labelRes, String moodTag) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(labelRes);
        b.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            CovenantJournal.checkIn(this, moodTag, null);
            if ("מתקשה".equals(moodTag)) {
                NotificationHelper.notifyDigest(this, "😕 צ׳ק-אין: מתקשה היום.");
            }
            build();
        });
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private Button bigButton(String text, int colorRes) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(ContextCompat.getColor(this, colorRes));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
