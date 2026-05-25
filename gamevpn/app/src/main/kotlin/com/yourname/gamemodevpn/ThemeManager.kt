package com.yourname.gamemodevpn

import android.content.Context
import android.os.Build

object ThemeManager {

    fun isDarkMode(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val nightMode = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else true // default dark
    }

    // Color palette
    object Dark {
        val BG       = 0xFF070C18.toInt()
        val CARD     = 0xFF0C1422.toInt()
        val CARD2    = 0xFF0A1220.toInt()
        val BORDER   = 0xFF172035.toInt()
        val ACCENT   = 0xFF00C8FF.toInt()
        val GREEN    = 0xFF00FFAA.toInt()
        val ORANGE   = 0xFFFF9500.toInt()
        val RED      = 0xFFFF3B6B.toInt()
        val PURPLE   = 0xFFA259FF.toInt()
        val TEXT     = 0xFFE8F4FF.toInt()
        val MUTED    = 0xFF3D5570.toInt()
        val MUTED2   = 0xFF6B8AAA.toInt()
    }

    object Light {
        val BG       = 0xFFF0F5FF.toInt()
        val CARD     = 0xFFFFFFFF.toInt()
        val CARD2    = 0xFFF7FAFF.toInt()
        val BORDER   = 0xFFDCE8F5.toInt()
        val ACCENT   = 0xFF0077CC.toInt()
        val GREEN    = 0xFF00AA66.toInt()
        val ORANGE   = 0xFFE07800.toInt()
        val RED      = 0xFFCC2244.toInt()
        val PURPLE   = 0xFF7733CC.toInt()
        val TEXT     = 0xFF0A1428.toInt()
        val MUTED    = 0xFF8899BB.toInt()
        val MUTED2   = 0xFF667799.toInt()
    }

    fun getColors(dark: Boolean) = if (dark) Dark else Light
}
