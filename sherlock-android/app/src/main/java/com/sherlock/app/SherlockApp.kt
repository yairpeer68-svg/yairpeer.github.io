package com.sherlock.app

import android.app.Application
import com.sherlock.app.util.CrashLog

class SherlockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
