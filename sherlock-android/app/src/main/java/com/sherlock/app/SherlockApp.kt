package com.sherlock.app

import android.app.Application
import com.sherlock.app.service.AutoCleanWorker
import com.sherlock.app.service.MonitorWorker
import com.sherlock.app.service.ScheduledSearchWorker

class SherlockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MonitorWorker.schedule(this)
        ScheduledSearchWorker.schedule(this)
        AutoCleanWorker.schedule(this)
    }
}
