package com.sherlock.app

import android.app.Application
import com.sherlock.app.service.MonitorWorker

class SherlockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MonitorWorker.schedule(this)
    }
}
