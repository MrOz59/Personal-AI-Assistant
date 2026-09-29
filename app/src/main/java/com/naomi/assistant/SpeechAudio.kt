package com.naomi.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

// Small audio helpers shared by the wake listener, the ears and voice training. The voice
// detection is plain energy against the room's own background — no model, no deps.

/** RMS loudness of each 20 ms frame of [audio]. */
private fun frameLoudness(audio: ShortArray, frame: Int): DoubleArray =
    DoubleArray(audio.size / frame) { f ->
        var sum = 0.0
        for (i in f * frame until (f + 1) * frame) sum += audio[i].toDouble() * audio[i]
        sqrt(sum / frame)
    }

/**
 * The loudness above which a frame counts as voice: a fraction of the way from the background
 * (the quietest fifth of frames) to the peak. Null when nothing stands out from the background.
 */
private fun voiceThreshold(loudness: DoubleArray, fraction: Double): Double? {
    if (loudness.size < 5) return null
    val sorted = loudness.sorted()
    val floor = sorted[loudness.size / 5]
    val peak = sorted.last()
    // Too little contrast (or too quiet overall) to call anything speech.
    if (peak < floor * 3 || peak < 200.0) return null
    return floor + (peak - floor) * fraction
}

/**
 * Sample index just past the last run of speech in [audio]: the last 20 ms frame clearly louder
 * than the background. Null when nothing stands out.
 */
internal fun lastSpeechEnd(audio: ShortArray, sampleRate: Int): Int? {
    val frame = sampleRate / 50
    val loudness = frameLoudness(audio, frame)
    val threshold = voiceThreshold(loudness, 0.2) ?: return null
    for (f in loudness.indices.reversed()) if (loudness[f] > threshold) return (f + 1) * frame
    return null
}

/**
 * [audio] with the silence around and between words dropped (a frame of margin kept either
 * side), so a voiceprint is made of voice. Empty when there's no speech in it.
 */
internal fun trimToSpeech(audio: ShortArray, sampleRate: Int): ShortArray {
    val frame = sampleRate / 50
    val loudness = frameLoudness(audio, frame)
    val threshold = voiceThreshold(loudness, 0.15) ?: return ShortArray(0)
    val voiced = BooleanArray(loudness.size) { loudness[it] > threshold }
    val keep = loudness.indices.filter { f ->
        voiced[f] || (f > 0 && voiced[f - 1]) || (f + 1 < voiced.size && voiced[f + 1])
    }
    val out = ShortArray(keep.size * frame)
    keep.forEachIndexed { i, f -> System.arraycopy(audio, f * frame, out, i * frame, frame) }
    return out
}

/**
 * Records [millis] of 16 kHz mono mic audio, blocking — call off the main thread, with the mic
 * free (wake listening paused). Used for the free-speech step of voice training.
 */
@SuppressLint("MissingPermission") // only called after RECORD_AUDIO is granted
internal fun recordMic(millis: Int, sampleRate: Int = 16000): ShortArray {
    val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), sampleRate)
    )
    val out = ShortArray(sampleRate * millis / 1000)
    return try {
        record.startRecording()
        var filled = 0
        while (filled < out.size) {
            val n = record.read(out, filled, out.size - filled)
            if (n <= 0) break
            filled += n
        }
        out.copyOf(filled)
    } finally {
        runCatching { record.stop() }
        record.release()
    }
}
