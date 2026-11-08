package com.felixos.drohne

import android.content.Context
import android.util.Base64
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileFilter
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Rückweg zu Claude: Flugprotokoll als Datei, Fotos von der Drohne holen,
 * beides ins Repo hochladen (Ordner fluege/<flug>/). Braucht einen GitHub-Token (Einstellungen).
 */
object FlightReport {
    private const val OWNER = "fiahless-beep"
    private const val REPO = "Drohne"
    private const val PREFS = "drohne"
    private const val KEY_TOKEN = "github_token"

    private lateinit var root: File
    private var flightDir: File? = null
    private var flightStartMs = 0L
    private var busy = false

    fun init(c: Context) {
        root = File(c.getExternalFilesDir(null), "fluege").apply { mkdirs() }
        DroneManager.addLogListener { line -> flightDir?.let { File(it, "protokoll.txt").appendText(line + "\n") } }
    }

    fun token(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)?.ifBlank { null }
    fun saveToken(c: Context, t: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, t.trim()).apply()

    /** Neuen Flugordner anlegen; ab jetzt landet jede Protokollzeile auch in der Datei. */
    fun startFlight(name: String) {
        flightStartMs = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date(flightStartMs))
        flightDir = File(root, "${stamp}_$name").apply { mkdirs() }
        DroneManager.log("Flugprotokoll: ${flightDir!!.name}/protokoll.txt")
    }

    val currentFlightName: String? get() = flightDir?.name

    /**
     * Fotos seit Flugbeginn von der Drohne laden und, wenn ein Token da ist, alles hochladen.
     * Läuft im Hintergrund; Fortschritt im Protokoll.
     */
    fun collectAndUpload(c: Context, onDone: (Boolean) -> Unit = {}) {
        if (busy) { DroneManager.log("Fotos: läuft schon"); return }
        val dir = flightDir ?: run {
            // Kein Flug in dieser Sitzung: trotzdem Ordner anlegen, damit „Fotos holen“ immer geht.
            startFlight("manuell"); flightStartMs = 0L; flightDir!!
        }
        busy = true
        Thread {
            var ok = false
            try {
                val photos = downloadPhotos(dir)
                DroneManager.log("Fotos geladen: ${photos.size}")
                val token = token(c)
                if (token == null) {
                    DroneManager.log("Kein GitHub-Token: Dateien bleiben auf dem Handy (${dir.absolutePath})")
                } else {
                    val files = (photos + File(dir, "protokoll.txt")).filter { it.exists() && it.length() > 0 }
                    var n = 0
                    files.forEach { f ->
                        if (uploadFile(token, "fluege/${dir.name}/${f.name}", f)) n++
                    }
                    DroneManager.log("Hochgeladen: $n von ${files.size} Dateien → github.com/$OWNER/$REPO/tree/main/fluege/${dir.name}")
                    ok = n == files.size
                }
            } catch (t: Throwable) {
                DroneManager.log("Fotos/Upload fehlgeschlagen: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                busy = false
                onDone(ok)
            }
        }.start()
    }

    /**
     * Flugaufzeichnungen des DJI-SDK (Ordner FlightRecord, Textdateien, verschlüsselt, mit DJIs Parser lesbar) ins Repo laden:
     * fluege/aufzeichnungen/<Datei>. Nur Dateien, die noch nicht hochgeladen wurden (Name+Größe gemerkt).
     * Felix 8.11.: Absturzanalyse ohne die 2-GB-Blackbox der Drohne.
     */
    fun uploadFlightRecords(c: Context, onDone: (Int) -> Unit = {}) {
        if (busy) { DroneManager.log("Aufzeichnungen: Upload läuft schon"); return }
        val token = token(c) ?: run { DroneManager.log("Kein GitHub-Token (Mehr … → GitHub-Token eintragen)"); onDone(-1); return }
        busy = true
        Thread {
            var n = 0
            try {
                val dirs = mutableListOf<File>()
                try {
                    val fm = dji.v5.manager.aircraft.flightrecord.FlightLogManager.getInstance()
                    fm.flightRecordPath?.let { dirs += File(it) }
                    fm.flyClogPath?.let { dirs += File(it) }
                } catch (t: Throwable) { DroneManager.log("Aufzeichnungspfad vom SDK nicht lesbar: ${t.message}") }
                // Rückfall: bekannte Orte unter dem App-Speicher
                c.getExternalFilesDir(null)?.let { base -> dirs += listOf(File(base, "FlightRecord"), File(base.parentFile, "FlightRecord"), File(base, "DJI/FlightRecord")) }
                val files = dirs.filter { it.isDirectory }.flatMap { d -> d.walkTopDown().maxDepth(2).filter { it.isFile && it.length() in 1..60_000_000 }.toList() }.distinctBy { it.absolutePath }
                DroneManager.log("Aufzeichnungen gefunden: ${files.size} in ${dirs.filter { it.isDirectory }.joinToString { it.absolutePath }}")
                val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val done = prefs.getStringSet("aufzeichnungen_hochgeladen", emptySet())!!.toMutableSet()
                val todo = files.filter { "${it.name}:${it.length()}" !in done }.sortedByDescending { it.lastModified() }.take(30)
                DroneManager.log("Davon neu: ${todo.size}")
                todo.forEach { f ->
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date(f.lastModified()))
                    if (uploadFile(token, "fluege/aufzeichnungen/${stamp}_${f.name}", f)) { n++; done += "${f.name}:${f.length()}" }
                }
                // Dazu die eigenen Flugprotokolle aller bisherigen Flüge (fluege/<flug>/protokoll.txt), die noch fehlen.
                root.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                    val pf = File(d, "protokoll.txt")
                    if (pf.isFile && pf.length() > 0 && "${d.name}/protokoll:${pf.length()}" !in done) {
                        if (uploadFile(token, "fluege/${d.name}/protokoll.txt", pf)) { n++; done += "${d.name}/protokoll:${pf.length()}" }
                    }
                }
                prefs.edit().putStringSet("aufzeichnungen_hochgeladen", done).apply()
                DroneManager.log("Aufzeichnungen hochgeladen: $n von ${todo.size} → github.com/$OWNER/$REPO/tree/main/fluege/aufzeichnungen")
            } catch (t: Throwable) {
                DroneManager.log("Aufzeichnungen: ${t.javaClass.simpleName}: ${t.message}")
            } finally { busy = false; onDone(n) }
        }.start()
    }

    /** Einen Text (z. B. Selbsttest) als Datei ins Repo laden; onDone(true) bei Erfolg, null-Token → false. */
    fun uploadText(c: Context, path: String, text: String, onDone: (Boolean) -> Unit) {
        val token = token(c) ?: run { DroneManager.log("Kein GitHub-Token (Mehr … → GitHub-Token eintragen)"); onDone(false); return }
        Thread {
            val ok = try {
                val f = File(c.cacheDir, File(path).name).apply { writeText(text) }
                uploadFile(token, path, f).also { f.delete() }
            } catch (t: Throwable) { DroneManager.log("Upload $path: ${t.message}"); false }
            if (ok) DroneManager.log("Hochgeladen: github.com/$OWNER/$REPO/blob/main/$path")
            onDone(ok)
        }.start()
    }

    /** Holt die Fotoliste von der Drohne und lädt alle Fotos, die nach Flugbeginn entstanden sind. */
    private fun downloadPhotos(dir: File): List<File> {
        val mm = MediaDataCenter.getInstance().mediaManager
        mm.setMediaFileDataSource(MediaFileListDataSource.Builder()
            .setLocation(CameraStorageLocation.SDCARD).setIndexType(ComponentIndexType.LEFT_OR_MAIN).build())
        await("Kameramodus Wiedergabe") { cb -> mm.enable(cb) }
        try {
            DroneManager.log("Hole Fotoliste von der Drohne …")
            await("Fotoliste") { cb ->
                mm.pullMediaFileListFromCamera(PullMediaFileListParam.Builder()
                    .filter(MediaFileFilter.PHOTO).count(50).mediaFileIndex(-1).build(), cb)
            }
            val all = mm.mediaFileListData?.data ?: emptyList()
            val wanted = all.filter { it.isValid && (flightStartMs == 0L || fileTimeMs(it) >= flightStartMs - 60_000) }
                .sortedBy { fileTimeMs(it) }
                .let { if (flightStartMs == 0L) it.takeLast(5) else it }
            DroneManager.log("Fotos auf der Drohne: ${all.size}, zu diesem Flug: ${wanted.size}")
            return wanted.mapNotNull { mf -> downloadOne(mf, File(dir, mf.fileName)) }
        } finally {
            try { await("Kameramodus zurück") { cb -> mm.disable(cb) } } catch (_: Throwable) {}
        }
    }

    private fun fileTimeMs(mf: MediaFile): Long = try {
        val d = mf.date
        java.util.GregorianCalendar(d.year, d.month - 1, d.day, d.hour, d.minute, d.second).timeInMillis
    } catch (_: Throwable) { 0L }

    private fun downloadOne(mf: MediaFile, out: File): File? {
        if (out.exists() && out.length() == mf.fileSize) return out
        val latch = CountDownLatch(1)
        var err: String? = null
        val fos = FileOutputStream(out)
        mf.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
            override fun onStart() {}
            override fun onProgress(total: Long, current: Long) {}
            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) { fos.write(data) }
            override fun onFinish() { latch.countDown() }
            override fun onFailure(error: IDJIError) { err = error.description(); latch.countDown() }
        })
        latch.await(120, TimeUnit.SECONDS)
        fos.close()
        return if (err == null && out.length() > 0) out.also { DroneManager.log("Foto ${it.name} (${it.length() / 1024} KB)") }
        else { DroneManager.log("Foto ${mf.fileName} fehlgeschlagen: ${err ?: "Zeitüberschreitung"}"); out.delete(); null }
    }

    private fun await(what: String, call: (CommonCallbacks.CompletionCallback) -> Unit) {
        val latch = CountDownLatch(1)
        var err: String? = null
        call(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { latch.countDown() }
            override fun onFailure(error: IDJIError) { err = error.description(); latch.countDown() }
        })
        if (!latch.await(60, TimeUnit.SECONDS)) throw IllegalStateException("$what: Zeitüberschreitung")
        err?.let { throw IllegalStateException("$what: $it") }
    }

    /** Eine Datei per GitHub-Contents-API anlegen oder ersetzen. */
    private fun uploadFile(token: String, path: String, f: File): Boolean {
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/contents/$path")
        val sha = try {
            (url.openConnection() as HttpURLConnection).run {
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                if (responseCode == 200) Regex("\"sha\"\\s*:\\s*\"([0-9a-f]+)\"").find(inputStream.bufferedReader().readText())?.groupValues?.get(1) else null
            }
        } catch (_: Throwable) { null }
        val body = StringBuilder()
        body.append("{\"message\":\"${if (path.startsWith("fluege/aufzeichnungen/")) "Flugaufzeichnung" else "Flug ${f.parentFile?.name}"}: ${f.name}\",")
        if (sha != null) body.append("\"sha\":\"$sha\",")
        body.append("\"content\":\"").append(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)).append("\"}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.connectTimeout = 30_000; conn.readTimeout = 120_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val msg = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Throwable) { null }
            DroneManager.log("Upload ${f.name}: HTTP $code ${msg ?: ""}")
            return false
        }
        return true
    }
}
