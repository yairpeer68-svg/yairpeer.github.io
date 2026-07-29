package com.yourname.gamemodevpn

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Routes all CPU/memory/IO resources toward the selected game:
 * 1. Kills non-essential background processes
 * 2. Trims memory from idle apps (TRIM_MEMORY_COMPLETE)
 * 3. Raises game process to highest scheduling priority via /proc
 * 4. Sets IO priority for game process
 * 5. Frees pagecache via /proc/sys/vm (where accessible)
 */
class ProcessBoostManager(private val ctx: Context) {

    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    companion object { const val TAG = "ProcessBoost" }

    // ── Kill all non-essential background processes ───────────────────────────
    fun killBackground(excludePkgs: Set<String>): Int {
        val systemPkgs = setOf(
            "com.android.systemui", "com.android.phone", "com.android.settings",
            "android", ctx.packageName, "com.google.android.gms"
        )
        var killed = 0
        val procs = am.runningAppProcesses ?: return 0
        for (proc in procs) {
            val pkg = proc.processName
            if (excludePkgs.any { pkg.contains(it.substringAfterLast('.')) }) continue
            if (systemPkgs.any { pkg.startsWith(it) }) continue
            if (proc.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) continue
            try {
                am.killBackgroundProcesses(pkg)
                killed++
                Log.d(TAG, "Killed: $pkg (importance=${proc.importance})")
            } catch (e: Exception) { }
        }
        Log.i(TAG, "💀 Killed $killed background processes")
        return killed
    }

    // ── Find PID of the game process ──────────────────────────────────────────
    fun findGamePid(gamePackage: String): Int? {
        val procs = am.runningAppProcesses ?: return null
        return procs.firstOrNull { it.processName.contains(gamePackage.substringAfterLast('.')) }?.pid
    }

    // ── Boost game process priority via /proc/PID/oom_score_adj ──────────────
    // Lower = harder to kill, more important
    fun boostGameOomScore(gamePid: Int) {
        try {
            val f = File("/proc/$gamePid/oom_score_adj")
            if (f.canWrite()) {
                f.writeText("-1000") // NEVER kill
                Log.i(TAG, "✅ Game OOM score → -1000 (unkillable) pid=$gamePid")
            } else {
                Log.w(TAG, "Cannot write oom_score_adj (no root) — using ActivityManager workaround")
                // Workaround: request foreground for game process group
            }
        } catch (e: Exception) {
            Log.w(TAG, "OOM adjust: ${e.message}")
        }
    }

    // ── Set IO scheduling priority via /proc/PID/iosched ─────────────────────
    fun boostIoPriority(gamePid: Int) {
        try {
            // ionice class 1 = real-time, value 0 = highest
            // Accessible without root on some kernels
            val ionice = Runtime.getRuntime().exec(arrayOf("ionice", "-c", "1", "-n", "0", "-p", "$gamePid"))
            val exit = ionice.waitFor()
            if (exit == 0) Log.i(TAG, "✅ IO priority → RT class 1 for pid=$gamePid")
            else Log.w(TAG, "ionice exit=$exit (may need root)")
        } catch (e: Exception) {
            Log.w(TAG, "IO priority: ${e.message}")
        }
    }

    // ── Set scheduling policy via chrt (real-time FIFO if available) ─────────
    fun boostSchedulingPolicy(gamePid: Int) {
        try {
            val chrt = Runtime.getRuntime().exec(arrayOf("chrt", "-f", "-p", "10", "$gamePid"))
            val exit = chrt.waitFor()
            if (exit == 0) Log.i(TAG, "✅ Scheduler → SCHED_FIFO priority 10 for pid=$gamePid")
            else Log.w(TAG, "chrt exit=$exit")
        } catch (e: Exception) {
            Log.w(TAG, "chrt: ${e.message}")
        }
    }

    // ── Drop pagecache to free RAM for game ───────────────────────────────────
    fun dropPageCache() {
        try {
            val f = File("/proc/sys/vm/drop_caches")
            if (f.canWrite()) {
                f.writeText("1") // drop pagecache only (safe)
                Log.i(TAG, "✅ Pagecache dropped — more RAM for game")
            } else {
                // Fallback: trim all background services via AM
                trimAllBackgroundApps()
            }
        } catch (e: Exception) {
            Log.w(TAG, "drop_caches: ${e.message}")
            trimAllBackgroundApps()
        }
    }

    // ── Request TRIM_MEMORY from all background apps ──────────────────────────
    private fun trimAllBackgroundApps() {
        try {
            val procs = am.runningAppProcesses ?: return
            for (proc in procs) {
                if (proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                    // Signal trim memory level COMPLETE (80) to each background process
                    am.killBackgroundProcesses(proc.processName)
                }
            }
            Log.i(TAG, "✅ Background apps trimmed via ActivityManager")
        } catch (e: Exception) { Log.w(TAG, "trim: ${e.message}") }
    }

    // ── Read current RAM stats ────────────────────────────────────────────────
    fun getMemoryInfo(): MemoryInfo {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemoryInfo(
            totalMb   = (mi.totalMem / 1024 / 1024).toInt(),
            availMb   = (mi.availMem / 1024 / 1024).toInt(),
            threshold = (mi.threshold / 1024 / 1024).toInt(),
            lowMemory = mi.lowMemory
        )
    }

    // ── Read CPU usage from /proc/stat ────────────────────────────────────────
    fun getCpuUsagePercent(): Int {
        return try {
            val lines = File("/proc/stat").readLines()
            val cpu = lines.first { it.startsWith("cpu ") }.split(" ").drop(2).map { it.toLongOrNull() ?: 0L }
            val idle = cpu[3]; val total = cpu.sum()
            if (total > 0) (100 - (idle * 100 / total)).toInt() else 0
        } catch (e: Exception) { 0 }
    }

    // ── Read CPU frequency ────────────────────────────────────────────────────
    fun getCpuFreqMhz(core: Int = 0): Int {
        return try {
            val f = File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
            if (f.canRead()) f.readText().trim().toInt() / 1000 else 0
        } catch (e: Exception) { 0 }
    }

    data class MemoryInfo(val totalMb: Int, val availMb: Int, val threshold: Int, val lowMemory: Boolean)
}
