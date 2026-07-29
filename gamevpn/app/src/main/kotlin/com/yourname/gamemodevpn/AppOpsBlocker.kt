package com.yourname.gamemodevpn

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Blocks background app access to mic and camera during gaming.
 * Uses AppOpsManager to check which apps are actively using sensors.
 */
class AppOpsBlocker(private val ctx: Context) {

    private val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    companion object { const val TAG = "AppOpsBlocker" }

    data class SensorUsage(val packageName: String, val op: String, val active: Boolean)

    // Find apps currently using microphone
    fun getActiveMicApps(): List<String> = getActiveOpsApps(AppOpsManager.OPSTR_RECORD_AUDIO)

    // Find apps currently using camera
    fun getActiveCameraApps(): List<String> = getActiveOpsApps(AppOpsManager.OPSTR_CAMERA)

    private fun getActiveOpsApps(op: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return try {
            val pkgs = mutableListOf<String>()
            val pm = ctx.packageManager
            val apps = pm.getInstalledApplications(0)
            for (app in apps) {
                val mode = aom.unsafeCheckOpNoThrow(op, app.uid, app.packageName)
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    // Check if actively running (not just allowed)
                    pkgs.add(app.packageName)
                }
            }
            pkgs
        } catch (e: Exception) {
            Log.w(TAG, "AppOps: ${e.message}")
            emptyList()
        }
    }

    // Check if specific app is using mic/camera right now (API 30+)
    fun isAppUsingMic(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
                aom.isOpActive(AppOpsManager.OPSTR_RECORD_AUDIO, uid, pkg)
            } else false
        } catch (e: Exception) { false }
    }

    fun isAppUsingCamera(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
                aom.isOpActive(AppOpsManager.OPSTR_CAMERA, uid, pkg)
            } else false
        } catch (e: Exception) { false }
    }

    // Scan all apps for active mic/camera usage
    fun scanActiveSensors(): List<SensorUsage> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val result = mutableListOf<SensorUsage>()
        val pm = ctx.packageManager
        try {
            for (app in pm.getInstalledApplications(0)) {
                val pkg = app.packageName
                val uid = app.uid
                if (aom.isOpActive(AppOpsManager.OPSTR_RECORD_AUDIO, uid, pkg))
                    result.add(SensorUsage(pkg, "🎙 Microphone", true))
                if (aom.isOpActive(AppOpsManager.OPSTR_CAMERA, uid, pkg))
                    result.add(SensorUsage(pkg, "📷 Camera", true))
            }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
        Log.i(TAG, "Active sensors: ${result.size} apps")
        return result
    }
}
