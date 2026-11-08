package com.felixos.drohne

import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightAssistantKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.camera.CameraSDCardState
import dji.sdk.keyvalue.value.flightassistant.VisionSystemWarning
import dji.sdk.keyvalue.value.flightcontroller.CompassSensorState
import dji.sdk.keyvalue.value.flightcontroller.FCConfigCompassCheckStatus
import dji.sdk.keyvalue.value.flightcontroller.FCConfigIMUStatus
import dji.sdk.keyvalue.value.flightcontroller.FCMotorStartFailureError
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.sdk.keyvalue.value.flightcontroller.IMUSensorState
import dji.sdk.keyvalue.value.flightcontroller.PositioningLevel
import dji.sdk.keyvalue.value.gimbal.GimbalCalibrationState
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.diagnostic.DJIDeviceHealthInfo
import dji.v5.manager.diagnostic.DeviceHealthManager
import dji.v5.manager.diagnostic.DeviceStatusManager
import dji.v5.manager.diagnostic.WarningLevel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Selbsttest nach dem Absturz (Felix 8.11.): fragt die Sensoren und die DJI-Gesundheitsmeldungen ab
 * und macht daraus eine Ampel-Liste. Läuft im Hintergrund, Ergebnis kommt per Callback auf dem Hauptthread.
 */
object SelfTest {
    enum class Level(val symbol: String, val rank: Int) { OK("✅", 0), INFO("ℹ️", 1), WARN("⚠️", 2), ERROR("❌", 3), UNKNOWN("⬜", -1) }

    data class Item(val group: String, val name: String, val value: String, val level: Level, val advice: String? = null)

    data class Result(val items: List<Item>, val health: List<String>, val status: String?) {
        val worst: Level get() = items.maxByOrNull { it.level.rank }?.level ?: Level.UNKNOWN
        val errors get() = items.count { it.level == Level.ERROR }
        val warnings get() = items.count { it.level == Level.WARN }
        val unknown get() = items.count { it.level == Level.UNKNOWN }
    }

    /** Werte, die DJI seit dem Verbinden gemeldet hat (Fehlerprotokoll). */
    private val healthLog = ConcurrentHashMap<String, String>()
    private var listening = false

    /** Nach SDK-Start aufrufen: DJI-Gesundheitsmeldungen mitschreiben, Änderungen ins Protokoll. */
    fun startListening() {
        if (listening) return
        listening = true
        try {
            DeviceHealthManager.getInstance().addDJIDeviceHealthInfoChangeListener { infos ->
                val now = infos.orEmpty().filter { (it.warningLevel() ?: WarningLevel.UNKNOWN) != WarningLevel.NORMAL }
                val keys = now.map { it.informationCode() ?: it.title().orEmpty() }.toSet()
                for (h in now) {
                    val k = h.informationCode() ?: h.title().orEmpty()
                    val text = fmtHealth(h)
                    if (healthLog.put(k, text) != text) DroneManager.log("DJI-Meldung: $text")
                }
                for (k in healthLog.keys.toList()) if (k !in keys) { healthLog.remove(k); DroneManager.log("DJI-Meldung erledigt: $k") }
            }
            DeviceStatusManager.getInstance().addDJIDeviceStatusChangeListener { _, new ->
                if (new != null) DroneManager.log("DJI-Status: ${new.name} – ${new.description()}")
            }
        } catch (t: Throwable) { DroneManager.log("Gesundheitsmeldungen nicht abonnierbar: ${t.message}") }
    }

    private fun fmtHealth(h: DJIDeviceHealthInfo): String {
        val lvl = when (h.warningLevel()) {
            WarningLevel.SERIOUS_WARNING -> "ERNST"; WarningLevel.WARNING -> "WARNUNG"; WarningLevel.CAUTION -> "ACHTUNG"
            WarningLevel.NOTICE -> "Hinweis"; else -> "?"
        }
        val d = h.description()?.takeIf { it.isNotBlank() && it != h.title() }?.let { " – $it" } ?: ""
        return "[$lvl] ${h.title()}$d"
    }

    /** Alles abfragen; Dauer höchstens ca. 6 s. */
    fun run(onDone: (Result) -> Unit) {
        Thread {
            val r = try { collect() } catch (t: Throwable) {
                Result(listOf(Item("Allgemein", "Selbsttest", "Abbruch: ${t.javaClass.simpleName} ${t.message}", Level.ERROR)), emptyList(), null)
            }
            DroneManager.log("Selbsttest: ${r.errors} Fehler, ${r.warnings} Warnungen, ${r.unknown} ohne Antwort")
            for (i in r.items) if (i.level == Level.ERROR || i.level == Level.WARN) DroneManager.log("  ${i.level.symbol} ${i.group}/${i.name}: ${i.value}")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(r) }
        }.start()
    }

    // ---- Abfragen -------------------------------------------------------------------------------------------

    private class Fetch {
        val latch: CountDownLatch
        val values = ConcurrentHashMap<String, Any>()
        val errors = ConcurrentHashMap<String, String>()
        private val wanted = mutableListOf<Pair<String, DJIKeyInfo<*>>>()
        constructor(vararg keys: Pair<String, DJIKeyInfo<*>>) { wanted += keys; latch = CountDownLatch(keys.size) }

        fun start() {
            for ((name, info) in wanted) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    ask(name, info as DJIKeyInfo<Any>)
                } catch (t: Throwable) { errors[name] = t.message ?: t.javaClass.simpleName; latch.countDown() }
            }
        }

        private fun <T : Any> ask(name: String, info: DJIKeyInfo<T>) {
            val key: DJIKey<T> = KeyTools.createKey(info)
            val cb = object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(v: T?) { if (v != null) values[name] = v; latch.countDown() }
                override fun onFailure(e: IDJIError) { errors[name] = e.description() ?: e.errorCode(); latch.countDown() }
            }
            KeyManager.getInstance().getValue(key, cb)
        }

        fun await(sec: Long) { latch.await(sec, TimeUnit.SECONDS) }
        inline fun <reified T> get(name: String): T? = values[name] as? T
        fun why(name: String): String = errors[name]?.let { "keine Antwort ($it)" } ?: "keine Antwort"
    }

    private fun collect(): Result {
        val f = Fetch(
            "fcConn" to FlightControllerKey.KeyConnection,
            "fw" to FlightControllerKey.KeyFirmwareVersion,
            "fwAll" to FlightControllerKey.KeyWholeDeviceFirmwareVersion,
            "sn" to FlightControllerKey.KeySerialNumber,
            "imu" to FlightControllerKey.KeyIMUStatus,
            "imuCheck" to FlightControllerKey.KeyIMUCheckStatus,
            "imuWarm" to FlightControllerKey.KeyIsIMUWarmingUp,
            "compass" to FlightControllerKey.KeyCompassState,
            "compassErr" to FlightControllerKey.KeyCompassHasError,
            "compassCheck" to FlightControllerKey.KeyCompassCheckStatus,
            "fcCheck" to FlightControllerKey.KeyFCCheckStatus,
            "gpsLevel" to FlightControllerKey.KeyGPSSignalLevel,
            "sats" to FlightControllerKey.KeyGPSSatelliteCount,
            "esc" to FlightControllerKey.KeyESCHasError,
            "motorProp" to FlightControllerKey.KeyIsMotorPropellerAbnormal,
            "motorStuck" to FlightControllerKey.KeyIsMotorStuck,
            "propInstall" to FlightControllerKey.KeyPropellerInstallState,
            "startErr" to FlightControllerKey.KeyMotorStartFailureError,
            "ultra" to FlightControllerKey.KeyUltrasonicHasError,
            "visionUsed" to FlightControllerKey.KeyIsVisionSensorUsed,
            "visionLevel" to FlightControllerKey.KeyVisionPositioningLevel,
            "visionWarn" to FlightAssistantKey.KeyVisionSystemWarning,
            "avoid" to FlightAssistantKey.KeyObstacleAvoidanceEnabled,
            "windWarn" to FlightControllerKey.KeyWindWarning,
            "gimbalConn" to GimbalKey.KeyConnection,
            "gimbalCal" to GimbalKey.KeyGimbalCalibrationStatus,
            "gimbalFw" to GimbalKey.KeyFirmwareVersion,
            "batPct" to BatteryKey.KeyChargeRemainingInPercent,
            "batTemp" to BatteryKey.KeyBatteryTemperature,
            "batVolt" to BatteryKey.KeyVoltage,
            "batCells" to BatteryKey.KeyCellVoltages,
            "batDmg" to BatteryKey.KeyIsCellDamaged,
            "batLowCell" to BatteryKey.KeyIsLowCellVoltageDetected,
            "batDiff" to BatteryKey.KeyIsVoltageDifferenceDetected,
            "batCycles" to BatteryKey.KeyNumberOfDischarges,
            "batLife" to BatteryKey.KeyLifetimeRemainingInPercent,
            "batSn" to BatteryKey.KeySerialNumber,
            "camConn" to CameraKey.KeyConnection,
            "camSensor" to CameraKey.KeyCameraHasSensorError,
            "sd" to CameraKey.KeyCameraSDCardState,
            "sdFree" to CameraKey.KeySDCardRemainSpace,
            "rcConn" to RemoteControllerKey.KeyConnection,
            "rcFw" to RemoteControllerKey.KeyFirmwareVersion,
            "link" to AirLinkKey.KeyLinkSignalQuality,
            "up" to AirLinkKey.KeyUpLinkQuality,
            "down" to AirLinkKey.KeyDownLinkQuality,
            "product" to ProductKey.KeyProductType,
        )
        f.start(); f.await(5)

        val items = mutableListOf<Item>()
        fun add(g: String, n: String, v: String, l: Level, a: String? = null) { items += Item(g, n, v, l, a) }
        fun unk(g: String, n: String, key: String) = add(g, n, f.why(key), Level.UNKNOWN)

        // --- Verbindung / Gerät
        val fcConn = f.get<Boolean>("fcConn")
        if (fcConn != true) {
            add("Gerät", "Drohne", if (fcConn == false) "nicht verbunden" else f.why("fcConn"), Level.ERROR, "Drohne einschalten, Fernbedienung per USB am Handy, DJI Fly beenden.")
        } else add("Gerät", "Drohne", "verbunden", Level.OK)
        f.get<Any>("product")?.let { add("Gerät", "Modell", it.toString(), Level.OK) }
        f.get<String>("sn")?.takeIf { it.isNotBlank() }?.let { add("Gerät", "Seriennummer", it, Level.OK) } ?: unk("Gerät", "Seriennummer", "sn")
        (f.get<String>("fwAll") ?: f.get<String>("fw"))?.let { add("Gerät", "Firmware", it, Level.OK) } ?: unk("Gerät", "Firmware", "fw")
        f.get<Boolean>("rcConn")?.let { add("Gerät", "Fernbedienung", if (it) "verbunden" else "nicht verbunden", if (it) Level.OK else Level.ERROR) } ?: unk("Gerät", "Fernbedienung", "rcConn")
        f.get<String>("rcFw")?.let { add("Gerät", "Firmware Fernbedienung", it, Level.OK) }
        val link = f.get<Int>("link"); val up = f.get<Int>("up"); val down = f.get<Int>("down")
        if (link != null || up != null || down != null) {
            val q = listOfNotNull(link, up, down).minOrNull() ?: 0
            add("Gerät", "Funkverbindung", "Qualität ${link ?: "?"}  (↑${up ?: "?"} ↓${down ?: "?"})", if (q >= 60) Level.OK else if (q >= 30) Level.WARN else Level.ERROR)
        } else unk("Gerät", "Funkverbindung", "link")

        // --- IMU
        val imus = f.get<List<*>>("imu")
        if (imus != null && imus.isNotEmpty()) {
            for (i in imus) {
                val s = i as dji.sdk.keyvalue.value.flightcontroller.IMUState
                val gy = s.gyroscopeState ?: IMUSensorState.UNKNOWN; val acc = s.accelerometerState ?: IMUSensorState.UNKNOWN
                add("IMU", "Gyroskop${if (imus.size > 1) " ${s.index}" else ""}", imuText(gy) + (s.gyroscopeBias?.let { "  Bias %.2f".format(it) } ?: ""), imuLevel(gy), imuAdvice(gy))
                add("IMU", "Beschleunigung${if (imus.size > 1) " ${s.index}" else ""}", imuText(acc) + (s.accelerometerBias?.let { "  Bias %.2f".format(it) } ?: ""), imuLevel(acc), imuAdvice(acc))
            }
        } else unk("IMU", "Gyroskop/Beschleunigung", "imu")
        f.get<FCConfigIMUStatus>("imuCheck")?.let {
            val (l, a) = when (it) {
                FCConfigIMUStatus.NORMAL, FCConfigIMUStatus.NONE -> Level.OK to null
                FCConfigIMUStatus.WARMING_UP -> Level.INFO to "Kurz warten, IMU wärmt noch auf."
                FCConfigIMUStatus.NEED_CALIBRATION -> Level.WARN to "IMU in DJI Fly kalibrieren (Sicherheit → Sensoren)."
                FCConfigIMUStatus.NEED_RESTART -> Level.WARN to "Drohne aus- und einschalten."
                FCConfigIMUStatus.NEED_CHECK_INSTALL_DIRECTION, FCConfigIMUStatus.OFFSET_EXCEPTION -> Level.ERROR to "IMU-Abweichung – nach dem Absturz nicht fliegen, DJI-Service."
                else -> Level.UNKNOWN to null
            }
            add("IMU", "Prüfstatus", it.name, l, a)
        }
        if (f.get<Boolean>("imuWarm") == true) add("IMU", "Aufwärmen", "läuft noch", Level.INFO)
        f.get<dji.sdk.keyvalue.value.flightcontroller.FCCheckStatus>("fcCheck")?.let { c ->
            val probs = listOfNotNull(
                "Gyroskop-Fehler".takeIf { c.gyroscopeError == true }, "Beschleunigungsdaten fehlerhaft".takeIf { c.accelarateDataError == true },
                "Barometer-Fehler".takeIf { c.barometerError == true }, "Barometer-Start fehlgeschlagen".takeIf { c.barometerInitError == true },
                "IMU-Start fehlgeschlagen".takeIf { c.imuInitError == true }, "IMU-Daten fehlerhaft".takeIf { c.imuDataError == true },
                "IMU-Richtung falsch".takeIf { c.imuDirectionParamError == true }, "Flugschreiber-Fehler".takeIf { c.dataRecoderError == true })
            val cal = listOfNotNull("Basis-Kalibrierung nötig".takeIf { c.basicIMUCalibrationNeeded == true }, "erweiterte Kalibrierung nötig".takeIf { c.advancedIMUCalibrationNeeded == true },
                "horizontale Kalibrierung nötig".takeIf { c.horizontalIMUCalibrateNeeded == true })
            if (c.attitudeTiltTooMuch == true) add("IMU", "Lage", "Drohne steht zu schräg", Level.WARN, "Auf ebenen Boden stellen.")
            when {
                probs.isNotEmpty() -> add("IMU", "Flugcontroller", probs.joinToString(", "), Level.ERROR, "Nicht fliegen – DJI-Service.")
                cal.isNotEmpty() -> add("IMU", "Flugcontroller", cal.joinToString(", "), Level.WARN, "In DJI Fly kalibrieren.")
                else -> add("IMU", "Flugcontroller", "keine Fehler, Barometer ok", Level.OK)
            }
        }

        // --- Kompass
        val comps = f.get<List<*>>("compass")
        if (comps != null && comps.isNotEmpty()) {
            for (c in comps) {
                val s = c as dji.sdk.keyvalue.value.flightcontroller.CompassState
                val st = s.compassSensorState ?: CompassSensorState.UNKNOWN
                add("Kompass", "Sensor${if (comps.size > 1) " ${s.index}" else ""}", compassText(st) + (s.compassSensorValue?.let { "  Wert $it" } ?: ""), compassLevel(st), compassAdvice(st))
            }
        } else unk("Kompass", "Sensor", "compass")
        f.get<Boolean>("compassErr")?.let { if (it) add("Kompass", "Fehlerbit", "gesetzt", Level.ERROR, "Weg von Metall/Beton; Kompass in DJI Fly kalibrieren.") }
        f.get<FCConfigCompassCheckStatus>("compassCheck")?.let {
            val (l, a) = when (it) {
                FCConfigCompassCheckStatus.NORMAL, FCConfigCompassCheckStatus.NONE -> Level.OK to null
                FCConfigCompassCheckStatus.NEED_CALIBRATION -> Level.WARN to "Kompass in DJI Fly kalibrieren."
                FCConfigCompassCheckStatus.NEED_RESTART -> Level.WARN to "Drohne neu starten."
                FCConfigCompassCheckStatus.NEED_CHECK_INSTALL_DIRECTION -> Level.ERROR to "Kompass verschoben – DJI-Service."
                else -> Level.UNKNOWN to null
            }
            add("Kompass", "Prüfstatus", it.name, l, a)
        }

        // --- GPS
        f.get<GPSSignalLevel>("gpsLevel")?.let {
            val n = it.name.removePrefix("LEVEL_")
            val l = when (it) { GPSSignalLevel.LEVEL_NONE, GPSSignalLevel.LEVEL_0, GPSSignalLevel.LEVEL_1 -> Level.WARN; GPSSignalLevel.UNKNOWN -> Level.UNKNOWN; else -> Level.OK }
            add("GPS", "Signal", "Stufe $n, ${f.get<Int>("sats") ?: "?"} Satelliten", l, if (l == Level.WARN) "Drinnen normal – draußen prüfen (≥ 10 Satelliten)." else null)
        } ?: unk("GPS", "Signal", "gpsLevel")

        // --- Motoren / Propeller
        f.get<Boolean>("esc")?.let { add("Motoren", "Regler (ESC)", if (it) "FEHLER" else "ok", if (it) Level.ERROR else Level.OK, if (it) "Nicht fliegen – DJI-Service." else null) } ?: unk("Motoren", "Regler (ESC)", "esc")
        f.get<Boolean>("motorProp")?.let { add("Motoren", "Motor/Propeller", if (it) "auffällig" else "ok", if (it) Level.ERROR else Level.OK, if (it) "Propeller auf Risse prüfen, Motoren von Hand drehen." else null) }
        f.get<Boolean>("motorStuck")?.let { if (it) add("Motoren", "Motor blockiert", "ja", Level.ERROR, "Fremdkörper/Verbiegung prüfen.") }
        f.get<dji.sdk.keyvalue.value.flightcontroller.PropellerInstallState>("propInstall")?.let { p ->
            val bad = listOfNotNull("vorne rechts".takeIf { p.frontRightInstallError == true }, "vorne links".takeIf { p.frontLeftInstallError == true },
                "hinten links".takeIf { p.behindLeftInstallError == true }, "hinten rechts".takeIf { p.behindRightInstallError == true })
            if (bad.isNotEmpty()) add("Motoren", "Propeller falsch montiert", bad.joinToString(", "), Level.ERROR, "Propeller mit/ohne Markierung passend zum Arm.")
        }
        f.get<FCMotorStartFailureError>("startErr")?.let {
            if (it != FCMotorStartFailureError.NONE && it != FCMotorStartFailureError.UNKNOWN)
                add("Motoren", "Startblockade", it.name, if (it.name.contains("USB") || it.name.contains("SIMULATOR")) Level.INFO else Level.WARN,
                    if (it.name.contains("USB")) "Normal am Kabel: Motoren starten nicht, solange USB an der Drohne steckt." else "DJI Fly nennt die Ursache im Klartext.")
            else add("Motoren", "Startblockade", "keine", Level.OK)
        }

        // --- Sicht / Hindernis
        f.get<VisionSystemWarning>("visionWarn")?.let {
            val l = when (it) { VisionSystemWarning.SAFE -> Level.OK; VisionSystemWarning.DANGEROUS -> Level.WARN; VisionSystemWarning.INVALID -> Level.WARN; else -> Level.UNKNOWN }
            add("Sicht", "Hindernissensoren", when (it) { VisionSystemWarning.SAFE -> "ok"; VisionSystemWarning.DANGEROUS -> "Hindernis nah"; VisionSystemWarning.INVALID -> "ungültig (dunkel? verschmutzt?)"; else -> "?" }, l,
                if (it == VisionSystemWarning.INVALID) "Linsen der Sichtsensoren reinigen, bei Tageslicht erneut prüfen. Nach dem Absturz ggf. in DJI Assistant 2 kalibrieren." else null)
        } ?: unk("Sicht", "Hindernissensoren", "visionWarn")
        f.get<dji.sdk.keyvalue.value.flightcontroller.PositioningLevelMsg>("visionLevel")?.level?.let {
            add("Sicht", "Sichtpositionierung", when (it) { PositioningLevel.STRONG -> "stark"; PositioningLevel.MEDDLE -> "mittel"; PositioningLevel.WEAK -> "schwach"; PositioningLevel.NONE -> "keine"; else -> "?" },
                when (it) { PositioningLevel.STRONG, PositioningLevel.MEDDLE -> Level.OK; PositioningLevel.WEAK -> Level.INFO; PositioningLevel.NONE -> Level.WARN; else -> Level.UNKNOWN },
                if (it == PositioningLevel.NONE) "Am Boden/in der Hand normal; im Schwebeflug über Textur prüfen." else null)
        }
        f.get<Boolean>("visionUsed")?.let { add("Sicht", "Sichtsensor aktiv", if (it) "ja" else "nein", if (it) Level.OK else Level.INFO) }
        f.get<Boolean>("ultra")?.let { add("Sicht", "Ultraschall/ToF unten", if (it) "FEHLER" else "ok", if (it) Level.ERROR else Level.OK, if (it) "Unterseite prüfen (Sensor beschädigt?)." else null) }
        f.get<Boolean>("avoid")?.let { add("Sicht", "Hindernisvermeidung", if (it) "eingeschaltet" else "AUS", if (it) Level.OK else Level.WARN, if (!it) "In DJI Fly einschalten (Umfliegen oder Bremsen)." else null) }

        // --- Gimbal / Kamera
        f.get<Boolean>("gimbalConn")?.let { add("Gimbal", "Verbindung", if (it) "ok" else "nicht verbunden", if (it) Level.OK else Level.ERROR) } ?: unk("Gimbal", "Verbindung", "gimbalConn")
        f.get<dji.sdk.keyvalue.value.gimbal.GimbalCalibrationStatusInfo>("gimbalCal")?.let {
            val st = it.status ?: GimbalCalibrationState.UNKNOWN
            add("Gimbal", "Kalibrierung", when (st) { GimbalCalibrationState.IDLE -> "ruhig (ok)"; GimbalCalibrationState.IN_PROGRESS -> "läuft ${it.progress ?: 0} %"; GimbalCalibrationState.FAILED -> "FEHLGESCHLAGEN"; else -> "?" },
                when (st) { GimbalCalibrationState.IDLE -> Level.OK; GimbalCalibrationState.IN_PROGRESS -> Level.INFO; GimbalCalibrationState.FAILED -> Level.ERROR; else -> Level.UNKNOWN },
                if (st == GimbalCalibrationState.FAILED) "Gimbal in DJI Fly neu kalibrieren; zittert er weiter → Service." else null)
        }
        f.get<String>("gimbalFw")?.let { add("Gimbal", "Firmware", it, Level.OK) }
        f.get<Boolean>("camConn")?.let { add("Kamera", "Verbindung", if (it) "ok" else "nicht verbunden", if (it) Level.OK else Level.ERROR) } ?: unk("Kamera", "Verbindung", "camConn")
        f.get<Boolean>("camSensor")?.let { add("Kamera", "Bildsensor", if (it) "FEHLER" else "ok", if (it) Level.ERROR else Level.OK) }
        f.get<CameraSDCardState>("sd")?.let {
            val free = f.get<Int>("sdFree")?.let { mb -> "  %.1f GB frei".format(mb / 1024.0) } ?: ""
            val l = when (it) { CameraSDCardState.NORMAL -> Level.OK; CameraSDCardState.NOT_INSERTED, CameraSDCardState.FULL, CameraSDCardState.INVALID, CameraSDCardState.READ_ONLY, CameraSDCardState.UNKNOWN_ERROR, CameraSDCardState.INVALID_FILE_SYSTEM -> Level.ERROR
                CameraSDCardState.FORMAT_NEEDED, CameraSDCardState.FORMAT_RECOMMENDED, CameraSDCardState.SLOW, CameraSDCardState.WRITING_SLOWLY -> Level.WARN; else -> Level.INFO }
            add("Kamera", "Speicherkarte", it.name.lowercase().replace('_', ' ') + free, l, if (l == Level.ERROR) "Karte prüfen/einsetzen – ohne Karte keine Fotos." else null)
        }

        // --- Akku
        f.get<Int>("batPct")?.let { add("Akku", "Ladung", "$it %", if (it >= 50) Level.OK else if (it >= 25) Level.INFO else Level.WARN) } ?: unk("Akku", "Ladung", "batPct")
        f.get<Double>("batTemp")?.let { add("Akku", "Temperatur", "%.0f °C".format(it), if (it in 15.0..45.0) Level.OK else Level.WARN, if (it < 15) "Akku vor dem Flug anwärmen (≥ 15 °C)." else if (it > 45) "Abkühlen lassen." else null) }
        val cells = f.get<List<*>>("batCells")?.mapNotNull { (it as? Int) }
        if (cells != null && cells.isNotEmpty()) {
            val spread = (cells.max() - cells.min())
            add("Akku", "Zellen", cells.joinToString(" / ") { "%.2f V".format(it / 1000.0) } + "  (Δ $spread mV)", if (spread <= 30) Level.OK else if (spread <= 80) Level.WARN else Level.ERROR,
                if (spread > 30) "Zellen ungleich – Akku voll laden und beobachten; bleibt es, Akku nicht mehr fliegen." else null)
        } else f.get<Int>("batVolt")?.let { add("Akku", "Spannung", "%.2f V".format(it / 1000.0), Level.OK) }
        if (f.get<Boolean>("batDmg") == true) add("Akku", "Zelle beschädigt", "JA", Level.ERROR, "Akku nicht mehr benutzen.")
        if (f.get<Boolean>("batLowCell") == true) add("Akku", "Zellspannung niedrig", "ja", Level.WARN, "Voll laden.")
        if (f.get<Boolean>("batDiff") == true) add("Akku", "Spannungsdifferenz", "erkannt", Level.WARN)
        val cyc = f.get<Int>("batCycles"); val life = f.get<Int>("batLife")
        if (cyc != null || life != null) add("Akku", "Zustand", listOfNotNull(cyc?.let { "$it Ladezyklen" }, life?.let { "$it % Lebensdauer" }).joinToString(", "), if ((life ?: 100) >= 60) Level.OK else Level.WARN)
        f.get<String>("batSn")?.takeIf { it.isNotBlank() }?.let { add("Akku", "Seriennummer", it, Level.OK) }

        // --- Wind
        f.get<Any>("windWarn")?.let { val n = it.toString(); if (n != "UNKNOWN" && n != "LEVEL_0") add("Umgebung", "Wind", n, if (n.contains("2")) Level.WARN else Level.INFO) }

        // --- DJI-Gesundheitsmeldungen (Klartext) und Gesamtstatus
        val health = try {
            DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos.orEmpty()
                .filter { (it.warningLevel() ?: WarningLevel.UNKNOWN) != WarningLevel.NORMAL }.map { fmtHealth(it) }
        } catch (t: Throwable) { listOf("Gesundheitsmeldungen nicht lesbar: ${t.message}") }
        val status = try { DeviceStatusManager.getInstance().currentDJIDeviceStatus?.let { "${it.name} – ${it.description()}" } } catch (_: Throwable) { null }
        for (h in health) add("DJI-Meldungen", h.substringBefore("]").removePrefix("["), h.substringAfter("] "),
            when { h.startsWith("[ERNST") -> Level.ERROR; h.startsWith("[WARNUNG") -> Level.WARN; h.startsWith("[ACHTUNG") -> Level.WARN; else -> Level.INFO })
        return Result(items, health, status)
    }

    private fun imuText(s: IMUSensorState) = when (s) {
        IMUSensorState.NORMAL_BIAS -> "ok"; IMUSensorState.MEDIUM_BIAS -> "mittlere Abweichung"; IMUSensorState.LARGE_BIAS -> "GROSSE Abweichung"
        IMUSensorState.WARMING_UP -> "wärmt auf"; IMUSensorState.IN_MOTION -> "in Bewegung (ruhig halten)"; IMUSensorState.CALIBRATING -> "kalibriert"
        IMUSensorState.CALIBRATION_FAILED -> "Kalibrierung FEHLGESCHLAGEN"; IMUSensorState.DATA_EXCEPTION -> "DATENFEHLER"; IMUSensorState.DISCONNECTED -> "GETRENNT"; else -> "?"
    }
    private fun imuLevel(s: IMUSensorState) = when (s) {
        IMUSensorState.NORMAL_BIAS -> Level.OK; IMUSensorState.MEDIUM_BIAS -> Level.WARN; IMUSensorState.WARMING_UP, IMUSensorState.IN_MOTION, IMUSensorState.CALIBRATING -> Level.INFO
        IMUSensorState.UNKNOWN -> Level.UNKNOWN; else -> Level.ERROR
    }
    private fun imuAdvice(s: IMUSensorState) = when (s) {
        IMUSensorState.MEDIUM_BIAS, IMUSensorState.LARGE_BIAS, IMUSensorState.CALIBRATION_FAILED -> "IMU in DJI Fly kalibrieren (Drohne kalt, auf ebener Fläche)."
        IMUSensorState.DATA_EXCEPTION, IMUSensorState.DISCONNECTED -> "Nach dem Absturz nicht fliegen – DJI-Service."
        else -> null
    }
    private fun compassText(s: CompassSensorState) = when (s) {
        CompassSensorState.NORMAL_MODULUS -> "ok"; CompassSensorState.WEAK_MODULUS -> "schwach"; CompassSensorState.SERIOUS_MODULUS -> "STARK GESTÖRT"
        CompassSensorState.UNCALIBRATED -> "nicht kalibriert"; CompassSensorState.CALIBRATING -> "kalibriert"; CompassSensorState.CALIBRATION_FAILED -> "Kalibrierung FEHLGESCHLAGEN"
        CompassSensorState.DIRECTION_EXCEPTION -> "RICHTUNGSFEHLER"; CompassSensorState.DATA_EXCEPTION -> "DATENFEHLER"; CompassSensorState.DISCONNECTED -> "GETRENNT"; else -> "?"
    }
    private fun compassLevel(s: CompassSensorState) = when (s) {
        CompassSensorState.NORMAL_MODULUS -> Level.OK; CompassSensorState.WEAK_MODULUS, CompassSensorState.UNCALIBRATED -> Level.WARN; CompassSensorState.CALIBRATING -> Level.INFO
        CompassSensorState.UNKNOWN -> Level.UNKNOWN; else -> Level.ERROR
    }
    private fun compassAdvice(s: CompassSensorState) = when (s) {
        CompassSensorState.WEAK_MODULUS, CompassSensorState.SERIOUS_MODULUS -> "Weg von Metall, Beton, Autos; draußen erneut prüfen, dann in DJI Fly kalibrieren."
        CompassSensorState.UNCALIBRATED, CompassSensorState.CALIBRATION_FAILED -> "Kompass in DJI Fly kalibrieren."
        CompassSensorState.DIRECTION_EXCEPTION, CompassSensorState.DATA_EXCEPTION, CompassSensorState.DISCONNECTED -> "Nach dem Absturz nicht fliegen – DJI-Service."
        else -> null
    }

    /** Lesbarer Bericht fürs Protokoll / zum Kopieren. */
    fun text(r: Result): String {
        val sb = StringBuilder()
        sb.append(when (r.worst) { Level.ERROR -> "❌ NICHT FLIEGEN – ${r.errors} Fehler"; Level.WARN -> "⚠️ ${r.warnings} Warnung(en) – vor dem Flug beheben"; Level.OK, Level.INFO -> "✅ Alles in Ordnung"; else -> "⬜ Keine Daten – Drohne verbunden?" })
        if (r.unknown > 0) sb.append("  (${r.unknown} ohne Antwort)")
        r.status?.let { sb.append("\nDJI-Gesamtstatus: $it") }
        sb.append("\n")
        for ((g, list) in r.items.groupBy { it.group }) {
            sb.append("\n").append(g.uppercase()).append("\n")
            for (i in list) {
                sb.append(i.level.symbol).append(' ').append(i.name).append(": ").append(i.value).append('\n')
                i.advice?.let { sb.append("     → ").append(it).append('\n') }
            }
        }
        return sb.toString()
    }
}
