package com.magen.family.ui;

import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.magen.family.R;
import com.magen.family.disguise.DisguiseManager;

/**
 * DisguiseActivity — בחירת מסכת ההסוואה (20 אפשרויות).
 *
 * המסך נבנה בקוד (בלי layout XML) כדי לשמור על גמישות: רשת של 20 כרטיסים,
 * הנוכחי מסומן. בחירה מחליפה מיד את האייקון והשם במגירת האפליקציות.
 *
 * יורש מ-BaseActivity כדי לכבד את שפת האפליקציה.
 */
public class DisguiseActivity extends BaseActivity {

    private GridLayout grid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_light));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.disguise_title);
        title.setTextSize(20);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.disguise_hint);
        hint.setTextSize(13);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        hint.setPadding(0, dp(6), 0, dp(14));
        root.addView(hint);

        grid = new GridLayout(this);
        grid.setColumnCount(3);
        root.addView(grid);

        scroll.addView(root);
        setContentView(scroll);

        buildGrid();
    }

    private void buildGrid() {
        grid.removeAllViews();
        int current = DisguiseManager.currentIndex(this);

        for (int i = 0; i < DisguiseManager.ALL.size(); i++) {
            DisguiseManager.Disguise d = DisguiseManager.ALL.get(i);
            grid.addView(buildCard(i, d, i == current));
        }
    }

    private View buildCard(int index, DisguiseManager.Disguise d, boolean selected) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8), dp(14), dp(8), dp(14));
        card.setBackgroundResource(selected
            ? R.drawable.bg_card_accent : R.drawable.bg_card);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        card.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(d.iconRes);
        int size = dp(52);
        icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        card.addView(icon);

        TextView label = new TextView(this);
        label.setText(d.labelRes);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(8), 0, 0);
        label.setTextColor(ContextCompat.getColor(this,
            selected ? R.color.accent_dark : R.color.text_secondary));
        card.addView(label);

        if (selected) {
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextColor(ContextCompat.getColor(this, R.color.accent));
            check.setTextSize(14);
            card.addView(check);
        }

        card.setOnClickListener(v -> {
            DisguiseManager.apply(this, index);
            Toast.makeText(this, R.string.disguise_applied, Toast.LENGTH_SHORT).show();
            buildGrid();
        });

        return card;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
