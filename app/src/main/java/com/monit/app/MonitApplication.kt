package com.monit.app

import android.app.Application
import com.monit.app.notifications.ReminderCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MonitApplication : Application() {
    @Inject internal lateinit var reminders: ReminderCoordinator
    override fun onCreate() {
        super.onCreate()
        reminders.start()
    }
}
