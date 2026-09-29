package com.naomi.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds only: keeps the audio behind recent voice decisions — each wake attempt's last
 * seconds, each voice-checked sentence, the free-speech training — with how it was read (where
 * each cut landed, the scores), in files/voice_debug. Pulled over adb, it lets the cutting and
 * scoring be checked against the real sound on a computer. Only the newest [KEEP] are kept.
 */
object VoiceDebug {
    private const val DIR = "voice_debug"
    private const val KEEP = 40
    private const val SAMPLE_RATE = 16000

    fun keep(context: Context, kind: String, audio: ShortArray, info: JSONObject) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val name = SimpleDateFormat("MMdd-HHmmss-SSS", Locale.ROOT).format(Date()) + "-" + kind
            File(dir, "$name.wav").writeBytes(wav(audio))
            File(dir, "$name.json").writeText(info.toString(1))
            dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }?.dropLast(KEEP)?.forEach {
                it.delete()
                File(dir, it.name.removeSuffix(".wav") + ".json").delete()
            }
        }.onFailure { android.util.Log.w("Naomi", "Voice debug not kept: ${it.message}") }
    }

    /**
     * A 16-bit WAV file's audio as 16 kHz mono PCM (first channel, linearly resampled), or null if
     * it can't be read. A data chunk whose size the writer never filled in is read to the end.
     */
    fun readWav16k(file: File): ShortArray? = runCatching {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 0
        var channels = 1
        var bits = 16
        var samples: ShortArray? = null
        var pos = 12
        while (pos + 8 <= b.limit() && samples == null) {
            val id = String(ByteArray(4) { b.get(pos + it) })
            val available = b.limit() - (pos + 8)
            val size = b.getInt(pos + 4).let { if (it < 0 || it > available) available else it }
            when (id) {
                "fmt " -> {
                    channels = b.getShort(pos + 10).toInt()
                    rate = b.getInt(pos + 12)
                    bits = b.getShort(pos + 22).toInt()
                }
                "data" -> samples = ShortArray(size / 2 / channels) { b.getShort(pos + 8 + it * 2 * channels) }
            }
            pos += 8 + size + (size and 1)
        }
        val src = samples
        if (bits != 16 || rate <= 0 || src == null || src.isEmpty()) return null
        if (rate == SAMPLE_RATE) src
        else ShortArray((src.size.toLong() * SAMPLE_RATE / rate).toInt()) { i ->
            val x = i.toDouble() * rate / SAMPLE_RATE
            val j = x.toInt()
            val a = src[minOf(j, src.size - 1)]
            val c = src[minOf(j + 1, src.size - 1)]
            (a + (c - a) * (x - j)).toInt().toShort()
        }
    }.getOrNull()

    /** 16 kHz mono 16-bit PCM as a WAV file. */
    private fun wav(audio: ShortArray): ByteArray {
        val data = audio.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        audio.forEach { b.putShort(it) }
        return b.array()
    }
}
