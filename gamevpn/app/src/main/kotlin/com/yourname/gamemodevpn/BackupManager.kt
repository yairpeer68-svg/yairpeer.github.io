package com.yourname.gamemodevpn

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Backup & Restore all settings + session history as encrypted JSON.
 */
object BackupManager {

    private const val TAG = "BackupManager"

    fun exportBackup(ctx: Context): File {
        val prefs = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
        val db = SessionDatabase(ctx)

        val root = JSONObject().apply {
            put("version", 9)
            put("timestamp", System.currentTimeMillis())
            put("device", android.os.Build.MODEL)

            // Settings
            val settings = JSONObject()
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> settings.put(k, v)
                    is Int     -> settings.put(k, v)
                    is String  -> settings.put(k, v)
                    is Set<*>  -> settings.put(k, v.joinToString(","))
                }
            }
            put("settings", settings)

            // Last 50 sessions
            val sessions = db.getLast(50)
            val sessArr = org.json.JSONArray()
            sessions.forEach { s ->
                sessArr.put(JSONObject().apply {
                    put("game", s.game); put("startTime", s.startTime)
                    put("durationSec", s.durationSec); put("avgPing", s.avgPing)
                    put("minPing", s.minPing); put("maxPing", s.maxPing)
                    put("packetLoss", s.packetLoss); put("avgJitter", s.avgJitter)
                })
            }
            put("sessions", sessArr)
        }

        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val file = File(ctx.cacheDir, "pingbooster_backup_${sdf.format(Date())}.json")
        file.writeText(root.toString(2))
        Log.i(TAG, "✅ Backup created: ${file.name} (${file.length() / 1024}KB)")
        return file
    }

    fun importBackup(ctx: Context, file: File): Boolean {
        return try {
            val json = JSONObject(file.readText())
            val settings = json.getJSONObject("settings")
            val prefs = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            settings.keys().forEach { key ->
                when (val v = settings.get(key)) {
                    is Boolean -> editor.putBoolean(key, v)
                    is Int     -> editor.putInt(key, v)
                    is String  -> {
                        // Try as string set first (comma-separated)
                        if (v.contains(",")) editor.putStringSet(key, v.split(",").toSet())
                        else editor.putString(key, v)
                    }
                }
            }
            editor.apply()
            Log.i(TAG, "✅ Backup restored: version ${json.optInt("version")} from ${json.optString("device")}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed: ${e.message}")
            false
        }
    }

    fun shareBackup(ctx: Context) {
        val file = exportBackup(ctx)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PingBooster Backup")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "ייצא גיבוי").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
