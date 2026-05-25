package com.yourname.gamemodevpn

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import android.widget.*

class AppSelectionActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("gameboost", Context.MODE_PRIVATE) }
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF070C18.toInt()

        // שחזר בחירות קודמות
        selected.addAll(prefs.getStringSet("selected_games", emptySet()) ?: emptySet())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070C18.toInt())
        }

        // כותרת
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 40, 24, 16)
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "🎮  בחר משחקים"; textSize = 20f
            setTextColor(0xFFE8F4FF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSave = Button(this).apply {
            text = "שמור"; textSize = 13f
            setTextColor(0xFF070C18.toInt())
            setBackgroundColor(0xFF00C8FF.toInt())
            setPadding(24, 8, 24, 8)
            setOnClickListener { saveAndFinish() }
        }
        header.addView(title); header.addView(btnSave)
        root.addView(header)

        // הסבר
        val hint = TextView(this).apply {
            text = "האפליקציות שתסמן יקבלו חיבור ישיר מלא\nכל השאר ייחסם בזמן המשחק"
            setTextColor(0xFF6B8AAA.toInt()); textSize = 12f
            setPadding(24, 0, 24, 20); gravity = Gravity.END
        }
        root.addView(hint)

        // רשימת אפליקציות
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 16, 40)
        }

        // טען אפליקציות + סנן משחקים
        val apps = getInstalledGames()
        for (app in apps) {
            val row = buildAppRow(app)
            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll)
        setContentView(root)
    }

    private fun buildAppRow(info: ApplicationInfo): View {
        val pm = packageManager
        val name = pm.getApplicationLabel(info).toString()
        val pkg  = info.packageName
        val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
        val isSelected = selected.contains(pkg)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 14, 16, 14)
            setBackgroundColor(if (isSelected) 0xFF0A1F3A.toInt() else 0xFF0C1422.toInt())
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 8)
            layoutParams = params
        }

        // אייקון
        val imgView = ImageView(this).apply {
            setImageDrawable(icon)
            val p = LinearLayout.LayoutParams(48, 48)
            p.marginEnd = 14
            layoutParams = p
        }

        // שם
        val nameView = TextView(this).apply {
            text = name; textSize = 13f
            setTextColor(if (isSelected) 0xFFE8F4FF.toInt() else 0xFF6B8AAA.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Checkbox
        val check = CheckBox(this).apply {
            isChecked = isSelected
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF00C8FF.toInt())
        }

        check.setOnCheckedChangeListener { _, checked ->
            if (checked) { selected.add(pkg); row.setBackgroundColor(0xFF0A1F3A.toInt()); nameView.setTextColor(0xFFE8F4FF.toInt()) }
            else         { selected.remove(pkg); row.setBackgroundColor(0xFF0C1422.toInt()); nameView.setTextColor(0xFF6B8AAA.toInt()) }
        }

        row.addView(imgView); row.addView(nameView); row.addView(check)
        return row
    }

    private fun getInstalledGames(): List<ApplicationInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && // לא מערכת
                app.packageName != packageName &&
                try { pm.getLaunchIntentForPackage(app.packageName) != null } catch (e: Exception) { false }
            }
            .sortedBy { pm.getApplicationLabel(it).toString() }
    }

    private fun saveAndFinish() {
        prefs.edit().putStringSet("selected_games", selected).apply()
        setResult(Activity.RESULT_OK)
        finish()
    }
}
