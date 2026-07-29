package com.yourname.gamemodevpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Central orchestrator — routes ALL device resources to the selected game:
 * CPU, RAM, IO, Network, Audio, Screen
 */
class GameResourceManager(private val ctx: Context) {

    private val cpu       = CpuBoostManager(ctx)
    private val proc      = ProcessBoostManager(ctx)
    private val handler   = Handler(Looper.getMainLooper())
    private var monitorThread: Thread? = null
    private var active = false
    private var targetPkgs = emptySet<String>()

    // Live stats
    var onStatsUpdate: ((ResourceStats) -> Unit)? = null

    companion object { const val TAG = "GameResourceMgr" }

    data class ResourceStats(
        val cpuPercent: Int,
        val cpuFreqMhz: Int,
        val availRamMb: Int,
        val totalRamMb: Int,
        val gamePid: Int,
        val killedProcesses: Int
    )

    fun activate(packages: Set<String>, targetFps: Int = 60) {
        if (active) return
        active = true
        targetPkgs = packages

        Log.i(TAG, "🚀 Activating FULL resource routing for: $packages")

        // Step 1: Kill background
        val killed = proc.killBackground(packages)

        // Step 2: Drop page cache / trim memory
        proc.dropPageCache()

        // Step 3: CPU boost (wake lock + sustained perf + hint manager)
        cpu.start(targetFps)

        // Step 4: Boost game process specifically
        Thread {
            Thread.sleep(2000) // Wait for game to fully launch
            for (pkg in packages) {
                val pid = proc.findGamePid(pkg)
                if (pid != null) {
                    Log.i(TAG, "🎮 Found game PID: $pid for $pkg")
                    proc.boostGameOomScore(pid)
                    proc.boostIoPriority(pid)
                    proc.boostSchedulingPolicy(pid)
                } else {
                    Log.w(TAG, "Game PID not found for $pkg yet")
                }
            }
        }.start()

        // Step 5: Continuous monitoring + re-killing background
        startMonitor(packages)

        Log.i(TAG, "✅ All resources routed to game (killed=$killed)")
    }

    private fun startMonitor(packages: Set<String>) {
        monitorThread = Thread {
            var cycle = 0
            while (active) {
                try {
                    Thread.sleep(3000)
                    cycle++

                    // Every 3 cycles (9s): kill any new background apps that spawned
                    if (cycle % 3 == 0) proc.killBackground(packages)

                    // Collect stats
                    val mem   = proc.getMemoryInfo()
                    val cpu   = proc.getCpuUsagePercent()
                    val freq  = proc.getCpuFreqMhz(0)
                    val pid   = packages.firstNotNullOfOrNull { proc.findGamePid(it) } ?: -1
                    val stats = ResourceStats(cpu, freq, mem.availMb, mem.totalMb, pid, 0)

                    handler.post { onStatsUpdate?.invoke(stats) }

                    if (mem.lowMemory) {
                        Log.w(TAG, "⚠️ Low memory — aggressive trim")
                        proc.killBackground(packages)
                        proc.dropPageCache()
                    }
                } catch (e: InterruptedException) { break }
                catch (e: Exception) { Log.e(TAG, e.message ?: "") }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun deactivate() {
        if (!active) return
        active = false
        cpu.stop()
        monitorThread?.interrupt()
        Log.i(TAG, "🛑 Resource routing deactivated")
    }

    fun getCpuBoostManager() = cpu
    fun getProcessBoostManager() = proc
}
