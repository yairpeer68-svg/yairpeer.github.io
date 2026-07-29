package com.yourname.gamemodevpn

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log

object ShortcutHelper {

    fun register(ctx: Context, games: Set<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val pm = ctx.packageManager

        val shortcuts = mutableListOf<ShortcutInfo>()

        // 1. Quick launch shortcut → opens app and auto-starts
        val launchIntent = Intent(ctx, MainActivity::class.java).apply {
            action = "ACTION_QUICK_LAUNCH"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        shortcuts.add(
            ShortcutInfo.Builder(ctx, "quick_launch")
                .setShortLabel("⚡ Game ON")
                .setLongLabel("הפעל מצב משחק")
                .setIntent(launchIntent)
                .build()
        )

        // 2. Per-game shortcuts (up to 4)
        games.take(3).forEachIndexed { i, pkg ->
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            val icon = try { Icon.createWithBitmap(pm.getApplicationIcon(pkg).let { d ->
                val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp); d.setBounds(0,0,96,96); d.draw(canvas); bmp
            }) } catch (e: Exception) { null }

            val intent = Intent(ctx, MainActivity::class.java).apply {
                action = "ACTION_LAUNCH_GAME"; putExtra("GAME_PKG", pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val builder = ShortcutInfo.Builder(ctx, "game_$i")
                .setShortLabel("🎮 $name")
                .setLongLabel("הפעל עבור $name")
                .setIntent(intent)
            if (icon != null) builder.setIcon(icon)
            shortcuts.add(builder.build())
        }

        // 4. Check ping shortcut
        shortcuts.add(
            ShortcutInfo.Builder(ctx, "check_ping")
                .setShortLabel("📊 בדוק פינג")
                .setLongLabel("בדוק פינג לכל השרתים")
                .setIntent(Intent(ctx, MainActivity::class.java).apply {
                    action = "ACTION_CHECK_PING"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                .build()
        )
        // 5. Settings shortcut
        shortcuts.add(
            ShortcutInfo.Builder(ctx, "open_settings")
                .setShortLabel("⚙️ הגדרות")
                .setLongLabel("פתח הגדרות GameBoost")
                .setIntent(Intent(ctx, SettingsActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                .build()
        )

        try {
            sm.dynamicShortcuts = shortcuts
            Log.i("Shortcuts", "✅ ${shortcuts.size} shortcuts registered")
        } catch (e: Exception) { Log.w("Shortcuts", e.message ?: "") }
    }
}
