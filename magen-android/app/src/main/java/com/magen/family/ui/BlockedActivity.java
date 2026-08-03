package com.magen.family.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.magen.family.R;

/**
 * BlockedActivity - מסך "תוכן חסום"
 * מוצג כשמזוהה ניסיון גישה לתוכן פורנו
 */
public class BlockedActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked);

        String blockedUrl = getIntent().getStringExtra("blocked_url");

        TextView tvUrl = findViewById(R.id.tv_blocked_url);
        if (tvUrl != null && blockedUrl != null) {
            // הצג דומיין בלבד, לא URL מלא
            String domain = blockedUrl
                .replace("https://", "")
                .replace("http://", "")
                .replace("www.", "");
            int slash = domain.indexOf('/');
            if (slash > 0) domain = domain.substring(0, slash);
            tvUrl.setText(domain);
        }

        // כפתור "חזור"
        View btnBack = findViewById(R.id.btn_go_home);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
                finish();
            });
        }

        // כפתור "אני ההורה" - מבקש PIN ואז מאפשר גישה
        View btnParent = findViewById(R.id.btn_parent_override);
        if (btnParent != null) {
            btnParent.setOnClickListener(v -> {
                Intent pin = new Intent(this, PinActivity.class);
                pin.putExtra("mode", "verify");
                startActivityForResult(pin, 1);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            // הורה אימת - סגור את מסך החסימה
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        // מנע לחיצת BACK מהמסך הזה - חייבים ללחוץ "חזור הביתה"
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
    }
}
