package com.felixos.drohne

import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * DJI-Einstellungen der Drohne lesen und setzen (Felix 13.11.: Einstellungen aus DJI Fly in die App, sortiert).
 * Alles läuft asynchron über den KeyManager; Fehler landen im Protokoll und im Rückruf.
 */
object DjiSettings {
    fun <T> read(info: DJIKeyInfo<T>, done: (T?) -> Unit) {
        if (Simulator.active || !DroneManager.sdkReady) return done(null)
        try {
            KeyManager.getInstance().getValue(KeyTools.createKey(info), object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(v: T?) = done(v)
                override fun onFailure(error: IDJIError) = done(null)
            })
        } catch (t: Throwable) { done(null) }
    }

    fun <T> write(info: DJIKeyInfo<T>, value: T, label: String, done: (Boolean) -> Unit = {}) {
        if (Simulator.active) { DroneManager.log("Simulation: $label"); return done(true) }
        if (!DroneManager.sdkReady) { DroneManager.log("$label: Drohne nicht verbunden"); return done(false) }
        try {
            KeyManager.getInstance().setValue(KeyTools.createKey(info), value, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { DroneManager.log("Einstellung gesetzt: $label"); done(true) }
                override fun onFailure(error: IDJIError) { DroneManager.log("$label fehlgeschlagen: ${error.description()}"); done(false) }
            })
        } catch (t: Throwable) { DroneManager.log("$label: ${t.message}"); done(false) }
    }

    fun action(info: DJIActionKeyInfo<EmptyMsg, EmptyMsg>, label: String, done: (String?) -> Unit = {}) {
        if (Simulator.active) { DroneManager.log("Simulation: $label"); return done(null) }
        if (!DroneManager.sdkReady) return done("Drohne nicht verbunden")
        try {
            KeyManager.getInstance().performAction(KeyTools.createKey(info), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) { DroneManager.log("$label gestartet"); done(null) }
                override fun onFailure(error: IDJIError) { DroneManager.log("$label fehlgeschlagen: ${error.description()}"); done(error.description() ?: "Fehler") }
            })
        } catch (t: Throwable) { done(t.message ?: "Fehler") }
    }
}
