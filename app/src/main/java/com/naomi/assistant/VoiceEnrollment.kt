package com.naomi.assistant

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * The owner's voiceprint: embeddings of several spoken "Naomi"s, averaged into one profile.
 * A wake phrase counts as the owner when its cosine similarity to the profile clears
 * [WakeService.similarityThreshold].
 *
 * One shared instance per process ([get]) so the wake service and the UI use the same loaded
 * model. The individual clip embeddings are stored (not just their average), tagged with the
 * model that produced them, so swapping the model invalidates the profile instead of silently
 * mis-scoring it.
 */
class VoiceEnrollment private constructor(context: Context) {

    private val verifier = SpeakerVerifier(context)
    private val file = File(context.filesDir, PROFILE_FILE)
    @Volatile private var profile: FloatArray? = null

    // A second print from free speech (training's last step): the "Naomi" print is tuned to one
    // word, this one to how the owner sounds saying anything — what commands get checked against.
    private val speechFile = File(context.filesDir, SPEECH_PROFILE_FILE)
    @Volatile private var speechProfile: FloatArray? = null

    // Re-check disk each time so a profile saved by the UI is picked up by WakeService.
    val isEnrolled: Boolean
        get() {
            if (profile == null) profile = load(file)
            return profile != null
        }

    val hasSpeechProfile: Boolean
        get() {
            if (speechProfile == null) speechProfile = load(speechFile)
            return speechProfile != null
        }

    /** Loads the speaker model ahead of the first wake. Blocking — call off the main thread. */
    fun warmUp() = verifier.warmUp()

    /** How well the enrollment clips agree with each other (leave-one-out similarity). */
    data class Outcome(val clips: Int, val consistency: Float, val weakest: Float)

    /**
     * Builds and saves the profile from [clips] (PCM-16, 16 kHz, one wake phrase each).
     * Returns null — leaving any previous profile in place — if fewer than [MIN_CLIPS] clips
     * could be embedded. [Outcome.consistency] approximates the owner's score at wake time.
     */
    fun enroll(clips: List<ShortArray>): Outcome? {
        val embeddings = clips.mapNotNull { verifier.embed(it) }
        if (embeddings.size < MIN_CLIPS) {
            android.util.Log.e("Naomi", "Enrollment failed: only ${embeddings.size} usable clips")
            return null
        }
        val leaveOneOut = embeddings.indices.map { i ->
            dot(centroid(embeddings.filterIndexed { j, _ -> j != i }), embeddings[i])
        }
        save(file, embeddings)
        profile = centroid(embeddings)
        return Outcome(embeddings.size, leaveOneOut.average().toFloat(), leaveOneOut.min())
            .also { android.util.Log.d("Naomi", "Enrolled voice: $it") }
    }

    /**
     * Builds the free-speech print from [audio] — some seconds of the owner talking about
     * anything — embedded in 3 s pieces of voice (pauses dropped) and averaged. False if there
     * wasn't enough speech in it.
     */
    fun enrollSpeech(audio: ShortArray): Boolean {
        val voice = trimToSpeech(audio, SAMPLE_RATE)
        val piece = SAMPLE_RATE * 3
        val embeddings = (0 until voice.size step piece)
            .map { voice.copyOfRange(it, minOf(it + piece, voice.size)) }
            .filter { it.size >= SAMPLE_RATE }
            .mapNotNull { verifier.embed(it) }
        if (embeddings.isEmpty()) {
            android.util.Log.e("Naomi", "Free-speech enrollment failed: ${voice.size / SAMPLE_RATE} s of voice")
            return false
        }
        save(speechFile, embeddings)
        speechProfile = centroid(embeddings)
        android.util.Log.d("Naomi", "Enrolled free speech: ${embeddings.size} pieces")
        return true
    }

    /**
     * Cosine similarity (−1..1, higher = more like the owner) of a wake phrase to the profile,
     * or null when there's no profile or the clip couldn't be embedded. Safe from any thread.
     */
    fun similarity(audio: ShortArray): Float? {
        val p = profile ?: load(file)?.also { profile = it } ?: return null
        val e = verifier.embed(audio) ?: return null
        return dot(p, e)
    }

    /**
     * How much an utterance — a command, any sentence — sounds like the owner: its voice (pauses
     * dropped) against both prints, best match. Null with no profile, or under half a second of
     * voice to judge by. Safe from any thread.
     */
    fun speakerSimilarity(audio: ShortArray): Float? {
        val prints = listOfNotNull(
            profile ?: load(file)?.also { profile = it },
            speechProfile ?: load(speechFile)?.also { speechProfile = it },
        )
        if (prints.isEmpty()) return null
        val voice = trimToSpeech(audio, SAMPLE_RATE)
        if (voice.size < SAMPLE_RATE / 2) return null
        val e = verifier.embed(voice) ?: return null
        return prints.maxOf { dot(it, e) }
    }

    fun clear() { profile = null; speechProfile = null; file.delete(); speechFile.delete() }

    private fun centroid(vs: List<FloatArray>): FloatArray {
        val c = FloatArray(vs[0].size)
        vs.forEach { v -> for (i in c.indices) c[i] += v[i] }
        val norm = sqrt(c.fold(0f) { acc, x -> acc + x * x })
        return if (norm < 1e-8f) c else FloatArray(c.size) { c[it] / norm }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun save(to: File, embeddings: List<FloatArray>) {
        try {
            DataOutputStream(to.outputStream().buffered()).use { out ->
                out.writeUTF(SpeakerVerifier.MODEL_ASSET)
                out.writeInt(embeddings.size)
                out.writeInt(embeddings[0].size)
                embeddings.forEach { e -> e.forEach { out.writeFloat(it) } }
            }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "Voice profile save failed: ${e.message}")
        }
    }

    private fun load(from: File): FloatArray? {
        if (!from.exists()) return null
        return try {
            DataInputStream(from.inputStream().buffered()).use { inp ->
                if (inp.readUTF() != SpeakerVerifier.MODEL_ASSET) {
                    android.util.Log.w("Naomi", "Voice profile was made by another model — clearing")
                    from.delete()
                    return null
                }
                val count = inp.readInt()
                val dim = inp.readInt()
                centroid(List(count) { FloatArray(dim) { inp.readFloat() } })
            }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "Voice profile load failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val PROFILE_FILE = "voice_profile.bin"
        private const val SPEECH_PROFILE_FILE = "voice_profile_speech.bin"
        private const val SAMPLE_RATE = 16000

        /** Fewest usable clips a profile may be built from. */
        const val MIN_CLIPS = 4

        @Volatile private var instance: VoiceEnrollment? = null

        fun get(context: Context): VoiceEnrollment =
            instance ?: synchronized(this) {
                instance ?: VoiceEnrollment(context.applicationContext).also { instance = it }
            }
    }
}
