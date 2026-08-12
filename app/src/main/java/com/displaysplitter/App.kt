package com.displaysplitter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.displaysplitter.settings.SettingsRepository
import com.displaysplitter.split.EngagementController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: SettingsRepository
        private set
    lateinit var controller: EngagementController
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        controller = EngagementController(this, settings, appScope)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            )
        )
    }

    companion object {
        const val CHANNEL_OVERLAY = "overlay"

        fun from(context: android.content.Context): App = context.applicationContext as App
    }
}
