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
        nativeDiagnose()
        if (CrashLog.hasReport(this)) {
            // Nach einem Absturz SDK diesmal nicht starten, damit der Bericht in Ruhe kopiert werden kann.
            DroneManager.log("Letzter Start ist abgestürzt: SDK startet, sobald der Absturzbericht geschlossen ist.")
            return
        }
        try {
            DroneManager.init(this)
        } catch (t: Throwable) {
            CrashLog.write(this, "Fehler beim Start des DJI SDK", t)
            DroneManager.log("SDK-Start fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Lädt DJIs Kernbibliotheken vorab in Abhängigkeitsreihenfolge und protokolliert jeden Fehler
     * im Klartext (dlopen-Meldung). Hintergrund: Absturz „No implementation found for
     * JNIProviderManager.native_init“ – die Bibliothek wird sonst still nicht geladen.
     */
    private fun nativeDiagnose() {
        try {
            val page = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            val dir = java.io.File(applicationInfo.nativeLibraryDir)
            DroneManager.log("Gerät: ${android.os.Build.SUPPORTED_ABIS.joinToString()}, Seitengröße $page, " +
                "${dir.list()?.size ?: 0} Bibliotheken in ${dir.name}")
        } catch (t: Throwable) { DroneManager.log("Diagnose: ${t.message}") }
        for (lib in listOf("c++_shared", "CORPProtobuf", "corpbase", "CORPCSDKCommon", "FlightRecordEngine", "corpsdk_jni")) {
            try {
                System.loadLibrary(lib)
                DroneManager.log("lib$lib geladen")
            } catch (t: Throwable) {
                DroneManager.log("lib$lib FEHLT: ${t.message}")
                CrashLog.write(this, "Bibliothek lib$lib lässt sich nicht laden (kein Absturz, nur Diagnose)", t)
            }
        }
    }
}
