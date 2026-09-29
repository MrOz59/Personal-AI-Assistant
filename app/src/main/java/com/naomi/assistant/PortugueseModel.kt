package com.naomi.assistant

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * Vosk's small Brazilian Portuguese model, for hearing a short answer over Naomi's question
 * ("sim", "não", "pelo zap") when she speaks Portuguese — what the English model does for "yes"
 * and "cancel" (see [WakeService]). It isn't bundled: the English model already makes the app
 * big. It's downloaded once (about 31 MB) into the app's files when Portuguese is in use. The
 * "Naomi" wake word stays on the English model: a name sounds the same in both languages.
 */
object PortugueseModel {

    enum class State { MISSING, DOWNLOADING, READY, FAILED }

    private const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
    private const val DIR = "vosk-model-pt"
    // Written last, once every file is in place: a download cut short is never taken for a model.
    private const val DONE = "complete"

    @Volatile var state = State.MISSING
        private set

    /** How much is downloaded, 0–100, while [state] is DOWNLOADING. */
    @Volatile var percent = 0
        private set

    /** Told when [state] or [percent] changes (on the download's thread). */
    @Volatile var onChange: (() -> Unit)? = null

    /** Where the model is, for Vosk's Model(path). */
    fun dir(context: Context) = File(context.filesDir, DIR)

    fun isReady(context: Context): Boolean = File(dir(context), DONE).exists()

    /**
     * Whether the model can be held to a list of words (it has a runtime graph), as answer mode
     * needs: without one, Vosk would ignore the list and take anything it hears for an answer.
     */
    fun takesGrammar(context: Context): Boolean = File(dir(context), "graph/Gr.fst").exists()

    /** The model's state, from its files until a download starts. */
    fun state(context: Context): State {
        if (state == State.MISSING && isReady(context)) state = State.READY
        return state
    }

    /** Downloads the model in the background, unless it's here already or on its way. */
    @Synchronized
    fun download(context: Context) {
        if (state(context) == State.READY || state == State.DOWNLOADING) return
        val app = context.applicationContext
        update(State.DOWNLOADING, 0)
        thread(name = "naomi-pt-model") {
            val ok = try {
                fetch(app)
                true
            } catch (e: Exception) {
                android.util.Log.w("Naomi", "Portuguese model download failed: ${e.message}")
                false
            }
            VoiceLog.add(app, if (ok) "Portuguese answers model downloaded" else "Portuguese answers model: download failed")
            update(if (ok) State.READY else State.FAILED, if (ok) 100 else 0)
        }
    }

    /** Downloads the zip, unpacks it beside the final folder, then moves it into place. */
    private fun fetch(context: Context) {
        val zip = File(context.cacheDir, "$DIR.zip")
        val part = File(context.filesDir, "$DIR.part")
        try {
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            http.newCall(Request.Builder().url(URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("no body")
                val total = body.contentLength()
                var read = 0L
                body.byteStream().use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) update(State.DOWNLOADING, (read * 100 / total).toInt().coerceAtMost(99))
                        }
                    }
                }
                if (total > 0 && read != total) throw IOException("download cut short ($read of $total bytes)")
            }
            part.deleteRecursively()
            unzip(zip, part)
            for (needed in listOf("am/final.mdl", "conf/model.conf")) {
                if (!File(part, needed).exists()) throw IOException("the model has no $needed")
            }
            val target = dir(context)
            target.deleteRecursively()
            if (!part.renameTo(target)) throw IOException("couldn't move the model into place")
            File(target, DONE).writeText(URL)
        } finally {
            zip.delete()
            part.deleteRecursively()
        }
    }

    /** Unpacks [zip] into [into], without the model's own top folder ("vosk-model-small-pt-0.3/"). */
    private fun unzip(zip: File, into: File) {
        val root = into.canonicalPath + File.separator
        ZipFile(zip).use { file ->
            for (entry in file.entries().asSequence()) {
                val path = entry.name.substringAfter('/', "")
                if (path.isEmpty()) continue
                val out = File(into, path)
                // An entry may not climb out of the folder ("../../").
                if (!out.canonicalPath.startsWith(root)) throw IOException("unsafe entry ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    file.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }

    private fun update(state: State, percent: Int) {
        if (state == this.state && percent == this.percent) return
        this.state = state
        this.percent = percent
        onChange?.invoke()
    }
}
