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

    private val appContext = context.applicationContext
    private val verifier = SpeakerVerifier(context)
    private val file = File(context.filesDir, PROFILE_FILE)
    // The prints are built from the stored clip embeddings on first use — off the main thread, as
    // it needs the model to tell voice from background (see [voiceOnly]).
    @Volatile private var profile: FloatArray? = null

    // A second print from free speech (training's last step): the "Naomi" print is tuned to one
    // word, this one to how the owner sounds saying anything — what commands get checked against.
    private val speechFile = File(context.filesDir, SPEECH_PROFILE_FILE)
    @Volatile private var speechProfile: FloatArray? = null

    // What silence and steady noise embed to: the reference for "no voice here".
    @Volatile private var noisePrint: FloatArray? = null

    // Checks the disk each time, so a profile saved by the UI is picked up by WakeService. Cheap:
    // safe on the main thread.
    val isEnrolled: Boolean get() = profile != null || file.exists()

    val hasSpeechProfile: Boolean get() = speechProfile != null || speechFile.exists()

    /** Loads the speaker model and the prints ahead of the first wake. Blocking — call off the main thread. */
    fun warmUp() {
        verifier.warmUp()
        wakePrint()
        speechPrint()
    }

    /**
     * How well the enrollment clips agree with each other (leave-one-out similarity), and how many
     * were left out as background rather than voice.
     */
    data class Outcome(val clips: Int, val consistency: Float, val weakest: Float, val dropped: Int = 0)

    /**
     * Builds and saves the profile from [clips] (PCM-16, 16 kHz, one wake phrase each).
     * Returns null — leaving any previous profile in place — if fewer than [MIN_CLIPS] clips
     * could be embedded. [Outcome.consistency] approximates the owner's score at wake time.
     */
    fun enroll(clips: List<ShortArray>): Outcome? {
        val embedded = clips.mapNotNull { verifier.embed(it) }
        // A clip cut from the background instead of the word would teach the print what silence
        // sounds like — and let silence through.
        val embeddings = voiceOnly(embedded)
        if (embeddings.size < MIN_CLIPS) {
            android.util.Log.e("Naomi", "Enrollment failed: only ${embeddings.size} usable clips (${embedded.size - embeddings.size} were background)")
            return null
        }
        val leaveOneOut = embeddings.indices.map { i ->
            dot(centroid(embeddings.filterIndexed { j, _ -> j != i }), embeddings[i])
        }
        save(file, embeddings)
        profile = centroid(embeddings)
        return Outcome(embeddings.size, leaveOneOut.average().toFloat(), leaveOneOut.min(), embedded.size - embeddings.size)
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
        val embeddings = voiceOnly((0 until voice.size step piece)
            .map { voice.copyOfRange(it, minOf(it + piece, voice.size)) }
            .filter { it.size >= SAMPLE_RATE }
            .mapNotNull { verifier.embed(it) })
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
        val p = wakePrint() ?: return null
        val e = verifier.embed(audio) ?: return null
        return dot(p, e)
    }

    /**
     * A sentence's voice check: how much it sounds like the owner, and how much there was to go
     * on. Noise and short sentences pull the owner's score down, so a low score from a [noisy]
     * or [short] one says less than the same score from a clear, long one.
     */
    data class SpeakerCheck(val similarity: Float, val voicedSeconds: Double, val snrDb: Double?) {
        val noisy: Boolean get() = snrDb != null && snrDb < NOISY_SNR_DB
        val short: Boolean get() = voicedSeconds < SHORT_VOICE_S
    }

    /**
     * How much an utterance — a command, any sentence — sounds like the owner: its voice (pauses
     * dropped) against both prints, best match. Null with no profile, or under half a second of
     * voice to judge by. Safe from any thread.
     */
    fun speakerCheck(audio: ShortArray): SpeakerCheck? {
        val prints = listOfNotNull(wakePrint(), speechPrint())
        if (prints.isEmpty()) return null
        val voice = trimToSpeech(audio, SAMPLE_RATE)
        if (voice.size < SAMPLE_RATE / 2) return null
        val e = verifier.embed(voice) ?: return null
        return SpeakerCheck(prints.maxOf { dot(it, e) }, voice.size.toDouble() / SAMPLE_RATE, snrDb(audio, SAMPLE_RATE))
    }

    fun clear() { profile = null; speechProfile = null; file.delete(); speechFile.delete() }

    private fun wakePrint(): FloatArray? = profile ?: loadPrint(file, "\"Naomi\"")?.also { profile = it }

    private fun speechPrint(): FloatArray? = speechProfile ?: loadPrint(speechFile, "free-speech")?.also { speechProfile = it }

    /**
     * A stored print, rebuilt from its clips without any that are background: training before
     * this check could cut a clip from the silence after the word, which taught the print what
     * silence sounds like — noise then passed the voice check and the owner's own voice scored low.
     */
    private fun loadPrint(from: File, what: String): FloatArray? {
        val all = load(from) ?: return null
        val voice = voiceOnly(all)
        if (voice.size < all.size) {
            VoiceLog.add(appContext, "Voice print: left out ${all.size - voice.size} of ${all.size} $what training clips — " +
                "they were silence, not your voice")
        }
        if (voice.isEmpty()) {
            VoiceLog.add(appContext, "Voice print: the $what training held no voice at all — train again")
            return null
        }
        return centroid(voice)
    }

    /** [embeddings] without those of background — silence or steady noise — rather than a voice. */
    private fun voiceOnly(embeddings: List<FloatArray>): List<FloatArray> {
        val noise = noisePrint ?: verifier.embed(noiseSample())?.also { noisePrint = it } ?: return embeddings
        return embeddings.filter { dot(it, noise) < BACKGROUND_LIKE }
    }

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

    /** The clip embeddings stored in [from], or null if there are none (or another model made them). */
    private fun load(from: File): List<FloatArray>? {
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
                List(count) { FloatArray(dim) { inp.readFloat() } }
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

        // Below this voice-over-background ratio a sentence counts as noisy (a street, a café),
        // and under this much voice as short: either way its score is only a rough guide.
        private const val NOISY_SNR_DB = 15.0
        private const val SHORT_VOICE_S = 1.5

        // An embedding this close to plain noise is of background, not a voice: silence and steady
        // noise land 0.66–0.88 from it, voices near 0.
        private const val BACKGROUND_LIKE = 0.5f

        /** One second of steady noise — what "no voice here" embeds to. */
        private fun noiseSample(): ShortArray {
            val random = java.util.Random(7)
            return ShortArray(SAMPLE_RATE) { (random.nextGaussian() * 400).toInt().toShort() }
        }

        @Volatile private var instance: VoiceEnrollment? = null

        fun get(context: Context): VoiceEnrollment =
            instance ?: synchronized(this) {
                instance ?: VoiceEnrollment(context.applicationContext).also { instance = it }
            }
    }
}
