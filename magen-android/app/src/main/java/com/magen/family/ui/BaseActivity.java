package com.magen.family.ui;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.magen.family.i18n.LocaleManager;

/**
 * BaseActivity — מחילה את שפת האפליקציה הנבחרת (עברית/אנגלית) על כל מסך.
 *
 * כל Activity שיורש מכאן מקבל את השפה שהמשתמש בחר, בלי קשר לשפת המכשיר.
 * המנגנון: attachBaseContext עוטף את ה-Context בשפה הנכונה עוד לפני
 * שה-layout מנופח, כך שכל getString/טעינת משאב מחזירים את השפה הנכונה
 * וגם כיווניות ה-RTL/LTR מתעדכנת.
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }
}
