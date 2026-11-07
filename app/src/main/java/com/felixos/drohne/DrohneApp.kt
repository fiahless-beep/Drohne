package com.felixos.drohne

import android.app.Application
import android.content.Context

class DrohneApp : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        CrashLog.install(this)
        try {
            com.cySdkyc.clx.Helper.install(this)
        } catch (t: Throwable) {
            CrashLog.write(this, "Fehler beim Laden des DJI SDK", t)
        }
    }
    override fun onCreate() {
        super.onCreate()
        try {
            DroneManager.init(this)
        } catch (t: Throwable) {
            CrashLog.write(this, "Fehler beim Start des DJI SDK", t)
            DroneManager.log("SDK-Start fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
