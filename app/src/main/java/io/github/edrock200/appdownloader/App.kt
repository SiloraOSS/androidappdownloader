package io.github.edrock200.appdownloader

import android.app.Application
import android.content.Context
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.work.Notifications
import io.github.edrock200.appdownloader.work.UpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: EntryRepository
        private set
    lateinit var settings: Settings
        private set
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        repository = EntryRepository(this)
        settings = Settings(this)
        controller = AppController(this, repository, settings, scope)
        Notifications.createChannel(this)
        UpdateScheduler.schedule(this, settings.current.checkIntervalHours)
    }
}

fun Context.app(): App = applicationContext as App
