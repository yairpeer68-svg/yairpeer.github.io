package com.yourname.gamemodevpn

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*

/**
 * Full settings screen — all feature toggles in one place.
 * Start via: startActivity(Intent(ctx, SettingsActivity::class.java))
 */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("gameboost", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0A1F2E.toInt()
        window.navigationBarColor = 0xFF0A1F2E.toInt()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A1F2E.toInt())
            setPadding(0, 0, 0, 48)
        }
        scroll.addView(root)
        setContentView(scroll)

        // Toolbar
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0D1F30.toInt())
            setPadding(16, 48, 16, 16)
            addView(Button(context).apply {
                text = "←"; setTextColor(0xFF00C8FF.toInt()); background = null; textSize = 20f
                setOnClickListener { finish() }
            })
            addView(TextView(context).apply {
                text = "הגדרות"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.gravity = Gravity.CENTER_VERTICAL }
            })
        })

        // Sections
        addSection(root, "🔒 אבטחה ו-DNS")
        addToggle(root, "DNS-over-HTTPS", "הצפנת DNS דרך Cloudflare 1.1.1.1", "use_doh", true)
        addToggle(root, "DNS-over-TLS", "חלופה ל-DoH עם פחות overhead", "use_dot", false)
        addToggle(root, "Certificate Pinning", "אימות תעודת SSL של שרתי DNS", "cert_pinning", true)
        addToggle(root, "Encrypted Preferences", "הצפנת הגדרות מקומיות", "use_encrypted_prefs", true)

        addSection(root, "⚡ VPN ורשת")
        addToggle(root, "Kill Switch", "חסום תעבורה אם ה-VPN נופל", "kill_switch", true)
        addToggle(root, "Split Tunnel", "נתב רק תעבורת משחקים דרך ה-VPN", "split_tunnel", true)
        addToggle(root, "IPv6", "תמיכה ב-IPv6 דרך ה-VPN", "ipv6_enabled", true)
        addToggle(root, "Traffic Obfuscation", "הסתר תעבורת VPN כ-HTTPS (DPI bypass)", "obfuscation", false)
        addToggle(root, "Multi-Path (WiFi+Cellular)", "שלח פאקטים דרך WiFi וסלולרי במקביל", "multipath", false)

        addSection(root, "🌐 DNS")
        addToggle(root, "NXDOMAIN Blocking", "חסום שרתי matchmaking רחוקים", "nxdomain_block", true)
        addToggle(root, "DNS Prefetch", "טעינה מוקדמת של DNS לשרתי משחק", "dns_prefetch", true)
        addToggle(root, "Happy Eyeballs", "תחרות IPv4/IPv6 לחיבור מהיר יותר", "happy_eyeballs", true)

        addSection(root, "📊 ניטור")
        addToggle(root, "Floating Ping Bubble", "בועת פינג מרחפת מעל משחקים", "floating_ping", false)
        addToggle(root, "Ping Spike Vibration", "רטט כשיש spike בפינג", "spike_vibration", true)
        addToggle(root, "Weekly Report", "דוח שבועי בהתראה", "weekly_report", true)
        addToggle(root, "Live Notification", "פינג בזמן אמת בשורת ההודעות", "live_notif", true)
        addSlider(root, "Spike Threshold", 40, 200, "spike_threshold", 80, "ms")

        addSection(root, "🎮 משחק")
        addToggle(root, "Anti-Lag Shot", "מקסימום משאבים 60 שניות לפני משחק", "anti_lag", true)
        addToggle(root, "BBR Congestion Control", "TCP BBR לרשתות עם jitter", "bbr_enabled", false)
        addToggle(root, "DSCP EF Marking", "סמן פאקטים כ-Expedited Forwarding", "dscp_ef", true)
        addToggle(root, "Auto Server Select", "בחר שרת מהיר אוטומטית", "auto_server", true)
        addToggle(root, "Gyro DnD", "הפעל שקט בהפניית מסך כלפי מטה", "gyro_dnd", true)

        addSection(root, "⚙️ מערכת")
        addToggle(root, "CPU Boost", "תעדוף CPU למשחק", "cpu_boost", true)
        addToggle(root, "GPU Boost", "תעדוף GPU", "gpu_boost", false)
        addToggle(root, "Kill Background Apps", "סגור אפליקציות ברקע", "kill_bg", false)
        addToggle(root, "Wake Lock", "מנע שינה של המכשיר במשחק", "wake_lock", true)
        addToggle(root, "Display Boost (120Hz)", "מקסם קצב רענון", "display_boost", true)
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 13f; setTextColor(0xFF00C8FF.toInt())
            setPadding(24, 28, 24, 8)
        })
    }

    private fun addToggle(parent: LinearLayout, label: String, desc: String, prefKey: String, default: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 12, 24, 12)
        }
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        textBlock.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
        })
        textBlock.addView(TextView(this).apply {
            text = desc; textSize = 12f; setTextColor(0xFF6B8AAA.toInt())
        })
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(prefKey, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(prefKey, checked).apply()
            }
        }
        row.addView(textBlock)
        row.addView(toggle)
        parent.addView(row)

        // Divider
        parent.addView(View(this).apply {
            setBackgroundColor(0xFF1E3A4F.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, 1).apply { setMargins(24, 0, 24, 0) }
        })
    }

    private fun addSlider(parent: LinearLayout, label: String, min: Int, max: Int, prefKey: String, default: Int, unit: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
        }
        val current = prefs.getInt(prefKey, default)
        val valueText = TextView(this).apply {
            text = "$label: $current$unit"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
        }
        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = current - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, prog: Int, user: Boolean) {
                    val v = prog + min
                    valueText.text = "$label: $v$unit"
                    prefs.edit().putInt(prefKey, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        row.addView(valueText)
        row.addView(seekBar)
        parent.addView(row)
    }
}
