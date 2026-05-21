package com.yourname.gamemodevpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class GameBoostWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.yourname.gamemodevpn.WIDGET_TOGGLE"

        fun updateAllWidgets(ctx: Context) {
            val awm = AppWidgetManager.getInstance(ctx)
            val ids = awm.getAppWidgetIds(android.content.ComponentName(ctx, GameBoostWidget::class.java))
            for (id in ids) updateWidget(ctx, awm, id)
        }

        fun updateWidget(ctx: Context, awm: AppWidgetManager, widgetId: Int) {
            val isRunning = GameModeVpnService.isRunning
            val prefs = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
            val ping = prefs.getInt("last_ping", 0)

            val views = RemoteViews(ctx.packageName, R.layout.widget_layout)
            views.setTextViewText(R.id.widget_status,
                if (isRunning) "⚡ פעיל" else "⏸ כבוי")
            views.setTextViewText(R.id.widget_ping,
                if (ping > 0) "${ping}ms" else "--")
            views.setInt(R.id.widget_bg, "setBackgroundColor",
                if (isRunning) 0xFF0A1F2E.toInt() else 0xFF0C1422.toInt())

            val toggleIntent = PendingIntent.getBroadcast(ctx, 0,
                Intent(ctx, GameBoostWidget::class.java).apply { action = ACTION_TOGGLE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_btn, toggleIntent)

            val openIntent = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_status, openIntent)

            awm.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(ctx: Context, awm: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(ctx, awm, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (GameModeVpnService.isRunning) {
                ctx.startService(Intent(ctx, GameModeVpnService::class.java).apply {
                    action = GameModeVpnService.ACTION_STOP
                })
            } else {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            updateAllWidgets(ctx)
        }
    }
}
