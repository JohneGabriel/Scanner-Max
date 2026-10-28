package br.obd.app

import android.app.Application

class ObdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ObdManager.init(this)
    }
}
