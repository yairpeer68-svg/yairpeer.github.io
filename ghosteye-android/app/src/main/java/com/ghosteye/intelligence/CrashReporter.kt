package com.ghosteye.intelligence

import android.content.Context
import android.os.Process
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object CrashReporter {
    private const val FILE = "last-crash.txt"
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = buildString {
                    appendLine("Ghost Eye ${BuildConfig.VERSION_NAME}")
                    appendLine("time=${Instant.now()}")
                    appendLine("thread=${thread.name}")
                    appendLine()
                    appendLine(throwable.stackTraceToString())
                }
                File(appContext.filesDir, FILE).writeText(text)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Preserve Android's normal fatal-crash semantics even on unusual
                // devices that do not install a default uncaught handler.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun hasCrash(context: Context): Boolean = File(context.filesDir, FILE).exists()
    fun read(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }
}
