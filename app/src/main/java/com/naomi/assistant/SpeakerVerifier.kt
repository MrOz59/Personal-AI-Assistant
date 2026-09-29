package com.naomi.assistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Speaker embedder: turns a voice clip into a unit-length voiceprint vector.
 *
 * Model: WeSpeaker CAM++ trained on VoxCeleb (CC BY 4.0, github.com/wenet-e2e/wespeaker),
 * bundled in assets. Picked over WeSpeaker ResNet34 because it scored better on sub-second
 * clips — the length of a spoken "Naomi" — and runs about twice as fast.
 *
 * Pipeline:
 *   raw PCM-16 (16 kHz mono, any length ≥ [MIN_SAMPLES])
 *     → FBankExtractor  → [1, frames, 80] CMN'd log-mel features
 *     → ONNX model      → [1, DIM] embedding, L2-normalised here
 *
 * No padding to a fixed length: the model pools over time, so padded silence would dilute
 * the voiceprint.
 */
class SpeakerVerifier(private val context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    @Volatile private var session: OrtSession? = null

    /** Loads the model now (a few hundred ms) so the first wake isn't delayed by it. */
    fun warmUp() { ensureSession() }

    @Synchronized
    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        return try {
            val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            env.createSession(model, opts).also { session = it }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "Speaker model load failed: ${e.message}")
            null
        }
    }

    /**
     * Voiceprint of [audio] (PCM-16, 16 kHz mono), or null if the clip is too short to judge
     * or the model failed. Safe to call from any thread.
     */
    fun embed(audio: ShortArray): FloatArray? {
        if (audio.size < MIN_SAMPLES) return null
        val sess = ensureSession() ?: return null
        return try {
            val (feats, frames) = FBankExtractor.compute(audio)
            val shape = longArrayOf(1, frames.toLong(), FBankExtractor.N_MELS.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), shape).use { input ->
                sess.run(mapOf(INPUT_NAME to input)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    l2Normalize((output[0].value as Array<FloatArray>)[0])
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "Speaker embed failed: ${e.message}")
            null
        }
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm < 1e-8f) v else FloatArray(v.size) { v[it] / norm }
    }

    companion object {
        const val MODEL_ASSET = "wespeaker_voxceleb_campplus.onnx"
        private const val INPUT_NAME = "feats"

        // 0.3 s — below this there's too little voice for a meaningful print.
        const val MIN_SAMPLES = 4800
    }
}
