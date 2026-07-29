package com.yourname.gamemodevpn

import android.app.Activity
import android.content.*
import android.graphics.Color
import android.net.VpnService
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

class OnboardingActivity : Activity() {

    private var step = 0
    private lateinit var container: FrameLayout
    private lateinit var btnNext: Button
    private lateinit var dots: LinearLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val steps = listOf(
        Triple("⚡", "ברוכים הבאים ל-PingBooster", "האפליקציה שהופכת את הפלאפון שלך למכונת גיימינג — DNS מוצפן, CPU מלא, Ping מינימלי"),
        Triple("🔒", "הרשאת VPN", "נדרשת הרשאה אחת לפתיחת ה-VPN המקומי.\nאין שרת חיצוני — הכל מעובד במכשיר שלך"),
        Triple("📍", "תצוגת Ping במשחק", "בועת הפינג תרחף מעל כל אפליקציה\nכדי לראות את הפינג בזמן אמת"),
        Triple("🎮", "בחר משחקים", "בחר את המשחקים שאתה משחק\nכדי לבחור שרתים מיטביים"),
        Triple("🧪", "בדיקת DNS Leak", "נבדוק שאין דליפת DNS — שכל הבקשות מוצפנות")
    )

    private val selectedGames = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Check if already completed
        if (getSharedPreferences("gameboost", Context.MODE_PRIVATE).getBoolean("onboarding_done", false)) {
            startMain(); return
        }
        window.statusBarColor = 0xFF0A1F2E.toInt()
        window.navigationBarColor = 0xFF0A1F2E.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A1F2E.toInt())
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        btnNext = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(32, 0, 32, 48) }
            setBackgroundColor(0xFF00C8FF.toInt())
            setTextColor(Color.BLACK)
            textSize = 16f
            text = "הבא ›"
            setOnClickListener { nextStep() }
        }

        root.addView(container)
        root.addView(dots)
        root.addView(btnNext)
        setContentView(root)

        buildDots()
        showStep(0)
    }

    private fun buildDots() {
        dots.removeAllViews()
        steps.forEachIndexed { i, _ ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(12, 12).apply { setMargins(6, 0, 6, 0) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (i == step) 0xFF00C8FF.toInt() else 0xFF1E3A4F.toInt())
                }
            }
            dots.addView(dot)
        }
    }

    private fun showStep(s: Int) {
        step = s
        buildDots()
        container.removeAllViews()
        btnNext.text = if (s == steps.lastIndex) "התחל!" else "הבא ›"
        container.addView(when (s) {
            0 -> buildWelcomeStep()
            1 -> buildVpnPermStep()
            2 -> buildOverlayPermStep()
            3 -> buildGameSelectStep()
            4 -> buildDnsLeakStep()
            else -> buildWelcomeStep()
        })
    }

    private fun nextStep() {
        if (step < steps.lastIndex) showStep(step + 1)
        else finish()
    }

    private fun buildCard(emoji: String, title: String, body: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 80, 48, 40)

            addView(TextView(context).apply {
                text = emoji; textSize = 72f; gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            })
            addView(TextView(context).apply {
                text = title; textSize = 24f; setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER; setPadding(0, 24, 0, 16)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            })
            addView(TextView(context).apply {
                text = body; textSize = 15f; setTextColor(0xFF6B8AAA.toInt())
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            })
        }
    }

    private fun buildWelcomeStep() = buildCard(steps[0].first, steps[0].second, steps[0].third)

    private fun buildVpnPermStep(): View {
        val card = buildCard(steps[1].first, steps[1].second, steps[1].third)
        card.addView(Button(this).apply {
            text = "אשר הרשאת VPN"; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = 32; gravity = android.view.Gravity.CENTER }
            setBackgroundColor(0xFF1E3A4F.toInt()); setTextColor(0xFF00C8FF.toInt())
            setOnClickListener {
                val intent = VpnService.prepare(this@OnboardingActivity)
                if (intent != null) startActivityForResult(intent, 100)
                else text = "✅ הרשאה אושרה"
            }
        })
        return card
    }

    private fun buildOverlayPermStep(): View {
        val card = buildCard(steps[2].first, steps[2].second, steps[2].third)
        card.addView(Button(this).apply {
            text = if (Settings.canDrawOverlays(this@OnboardingActivity)) "✅ הרשאה אושרה" else "אשר הצגה מעל אפליקציות"
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = 32; gravity = android.view.Gravity.CENTER }
            setBackgroundColor(0xFF1E3A4F.toInt()); setTextColor(0xFF00C8FF.toInt())
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@OnboardingActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                } else text = "✅ הרשאה אושרה"
            }
        })
        return card
    }

    private fun buildGameSelectStep(): View {
        val layout = buildCard(steps[3].first, steps[3].second, "")
        val games = listOf(
            "CoD Mobile" to "com.activision.callofduty.shooter",
            "PUBG Mobile" to "com.tencent.ig",
            "Free Fire"  to "com.dts.freefireth",
            "Fortnite"   to "com.epicgames.fortnite"
        )
        games.forEach { (name, pkg) ->
            layout.addView(CheckBox(this).apply {
                text = name; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
                isChecked = selectedGames.contains(pkg)
                setOnCheckedChangeListener { _, chk -> if (chk) selectedGames.add(pkg) else selectedGames.remove(pkg) }
            })
        }
        return layout
    }

    private fun buildDnsLeakStep(): View {
        val layout = buildCard(steps[4].first, steps[4].second, steps[4].third)
        val result = TextView(this).apply {
            text = "לחץ לבדיקה"; setTextColor(0xFF6B8AAA.toInt()); textSize = 14f
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(result)
        layout.addView(Button(this).apply {
            text = "בדוק עכשיו"
            setBackgroundColor(0xFF1E3A4F.toInt()); setTextColor(0xFF00C8FF.toInt())
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = 16; gravity = android.view.Gravity.CENTER }
            setOnClickListener {
                text = "בודק..."
                scope.launch {
                    val leak = withContext(Dispatchers.IO) {
                        try { DoHResolver.runLeakTest() } catch (_: Exception) { null }
                    }
                    if (leak == null) {
                        result.text = "❌ שגיאה בבדיקה"
                        result.setTextColor(0xFFFF3B6B.toInt())
                    } else if (leak.leaked) {
                        result.text = "⚠️ DNS Leak זוהה!\nSystemDNS: ${leak.systemIp} vs DoH: ${leak.dohIp}"
                        result.setTextColor(0xFFFF9500.toInt())
                    } else {
                        result.text = "✅ אין דליפת DNS\nכל הבקשות מוצפנות (${leak.latencyMs}ms)"
                        result.setTextColor(0xFF00FFAA.toInt())
                    }
                    this@apply.text = "בדוק שוב"
                }
            }
        })
        return layout
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 100 && resultCode == RESULT_OK) android.util.Log.i("Onboarding", "VPN permission granted")
    }

    private fun startMain() {
        getSharedPreferences("gameboost", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_done", true)
            .putStringSet("selected_games", selectedGames)
            .apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun finish() {
        startMain()
        scope.cancel()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
