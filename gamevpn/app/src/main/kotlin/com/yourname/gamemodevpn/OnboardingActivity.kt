package com.yourname.gamemodevpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.*
import android.view.*
import android.widget.*

class OnboardingActivity : Activity() {

    private var step = 0
    private lateinit var container: FrameLayout
    private lateinit var btnNext: Button
    private lateinit var dots: LinearLayout

    private val steps = listOf(
        Triple("⚡", "ברוכים הבאים ל-PingBooster", "האפליקציה שהופכת את הפלאפון שלך למכונת גיימינג — DNS מוצפן, CPU מלא, Ping מינימלי"),
        Triple("🌐", "DNS-over-HTTPS", "כל שאילתת DNS מוצפנת ל-Cloudflare 1.1.1.1\nאפס DNS leak, אפס מעקב מהספק"),
        Triple("🖥", "CPU מנותב למשחק", "WakeLock + PerformanceHintManager + big.LITTLE affinity\nכל ליבת הביצועים עובדת בשבילך"),
        Triple("🎮", "הפעלה אוטומטית", "פותח את CoD? האפליקציה מופעלת לבד\nסוגר? חוזר לנורמל אוטומטי"),
        Triple("📊", "סטטיסטיקות מלאות", "Ping, Loss, Jitter, טמפרטורה, CPU\nהיסטוריה, heatmap ויצוא CSV"),
        Triple("✅", "הכל מוכן!", "לחץ על כפתור ⏻ כדי להפעיל\nהוסף את ה-Tile להגדרות מהירות לגישה נוחה")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSharedPreferences("gameboost", Context.MODE_PRIVATE).getBoolean("onboarded", false)) {
            startMain(); return
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = 0xFF070C18.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF070C18.toInt())
            gravity = android.view.Gravity.CENTER; setPadding(32, 60, 32, 60)
        }

        container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(container)

        // Dots
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 24 }
        }
        root.addView(dots)

        btnNext = Button(this).apply {
            textSize = 16f; setTextColor(0xFF070C18.toInt()); setBackgroundColor(0xFF00C8FF.toInt())
            setPadding(0, 18, 0, 18)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { advance() }
        }
        root.addView(btnNext)
        setContentView(root)
        showStep(0)
    }

    private fun showStep(i: Int) {
        step = i
        val (icon, title, sub) = steps[i]

        container.removeAllViews()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Icon
        inner.addView(TextView(this).apply {
            text = icon; textSize = 80f; gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 32 }
        })

        // Title
        inner.addView(TextView(this).apply {
            text = title; textSize = 24f; setTextColor(0xFFE8F4FF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 16 }
        })

        // Subtitle
        inner.addView(TextView(this).apply {
            text = sub; textSize = 14f; setTextColor(0xFF6B8AAA.toInt())
            gravity = android.view.Gravity.CENTER; lineSpacingMultiplier = 1.5f
        })

        container.addView(inner)

        // Dots
        dots.removeAllViews()
        steps.indices.forEach { idx ->
            dots.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(if (idx == i) 28 else 10, 10).also { it.marginEnd = 6 }
                setBackgroundColor(if (idx == i) 0xFF00C8FF.toInt() else 0xFF172035.toInt())
            })
        }

        btnNext.text = if (i < steps.size - 1) "הבא →" else "בואו נתחיל ⚡"
    }

    private fun advance() {
        if (step < steps.size - 1) {
            showStep(step + 1)
            HapticManager.tick(this)
        } else {
            getSharedPreferences("gameboost", Context.MODE_PRIVATE).edit().putBoolean("onboarded", true).apply()
            startMain()
        }
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
