package com.sherlock.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sherlock.app.MainActivity

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_MONITOR = "monitor_channel"
        const val CHANNEL_SEARCH = "search_channel"
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            "ניטור פרופילים",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "התראות על שינויים בפרופילים מנוטרים"
        }

        val searchChannel = NotificationChannel(
            CHANNEL_SEARCH,
            "חיפושים",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "התראות על חיפושים מתוזמנים"
        }

        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(searchChannel)
    }

    fun showProfileChangeNotification(siteName: String, username: String, changeType: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("שינוי בפרופיל: $username")
            .setContentText("$changeType ב-$siteName")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun showSearchCompleteNotification(username: String, found: Int, total: Int) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SEARCH)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("חיפוש הושלם: $username")
            .setContentText("נמצאו $found מתוך $total אתרים")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
