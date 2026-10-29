package br.obd.app

import android.app.Application

class ObdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ObdManager.init(this)
        Updater.init(this)
        runCatching { ReminderWorker.schedule(this) }
        runCatching { Updater.schedule(this) }
        QuickNotif.refresh(this)
    }
}
