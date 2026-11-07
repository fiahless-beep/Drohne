package com.felixos.drohne

import android.app.Application
import android.content.Context

class DrohneApp : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }
    override fun onCreate() {
        super.onCreate()
        DroneManager.init(this)
    }
}
