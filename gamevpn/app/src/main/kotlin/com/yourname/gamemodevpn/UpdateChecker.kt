package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL

/**
 * Checks for app updates from a remote JSON endpoint.
 * Can point to GitHub releases API or your own server.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val CURRENT_VERSION = 9
    // Point to your GitHub releases or self-hosted JSON
    private const val UPDATE_URL = "https://raw.githubusercontent.com/yourname/pingbooster/main/version.json"

    data class UpdateInfo(
        val available: Boolean,
        val latestVersion: Int,
        val currentVersion: Int,
        val changelog: String,
        val downloadUrl: String
    )

    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        return@withContext try {
            val conn = URL(UPDATE_URL).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val latest = json.optInt("version", CURRENT_VERSION)
            val changelog = json.optString("changelog", "")
            val downloadUrl = json.optString("downloadUrl", "")
            val available = latest > CURRENT_VERSION
            if (available) Log.i(TAG, "🆕 Update available: v$CURRENT_VERSION → v$latest")
            else Log.i(TAG, "✅ App is up to date (v$CURRENT_VERSION)")
            UpdateInfo(available, latest, CURRENT_VERSION, changelog, downloadUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            UpdateInfo(false, CURRENT_VERSION, CURRENT_VERSION, "", "")
        }
    }
}
