package com.naomi.assistant

import kotlin.math.*

/**
 * Kaldi-compatible 80-dim log-mel filterbank, configured exactly like WeSpeaker's front-end
 * (torchaudio.compliance.kaldi.fbank with window_type=hamming, dither=0, use_energy=false),
 * followed by per-utterance mean normalisation (CMN) — the features the speaker model was
 * trained on. Any mismatch here silently wrecks the voiceprints, so FBankExtractorTest checks
 * the output against kaldi-native-fbank.
 *
 * Input is raw 16 kHz PCM-16 in its int16 range: WeSpeaker does NOT scale samples to [-1, 1].
 * Pure Kotlin, no deps.
 */
object FBankExtractor {

    private const val SAMPLE_RATE  = 16000
    private const val FRAME_LENGTH = 400     // 25 ms
    private const val FRAME_SHIFT  = 160     // 10 ms
    private const val N_FFT        = 512     // FRAME_LENGTH rounded up to a power of two
    const  val N_MELS              = 80
    private const val LOW_FREQ     = 20.0
    private const val HIGH_FREQ    = SAMPLE_RATE / 2.0  // Kaldi high_freq=0 → Nyquist
    private const val PREEMPHASIS  = 0.97
    private const val LOG_FLOOR    = 1.1920929e-7       // FLT_EPSILON, Kaldi's floor before the log

    private val HAMMING = DoubleArray(FRAME_LENGTH) { n ->
        0.54 - 0.46 * cos(2 * PI * n / (FRAME_LENGTH - 1))
    }

    // Mel filterbank weights [N_MELS × N_FFT/2] — computed once at first access.
    private val MEL_WEIGHTS: Array<DoubleArray> by lazy { buildMelWeights() }

    private fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)

    /**
     * Kaldi MelBanks: triangles evenly spaced on the mel scale, each evaluated at every FFT
     * bin's own mel frequency (not snapped to bin edges). Bins run 0 until N_FFT/2 — Kaldi
     * never uses the Nyquist bin.
     */
    private fun buildMelWeights(): Array<DoubleArray> {
        val melLow = mel(LOW_FREQ)
        val delta = (mel(HIGH_FREQ) - melLow) / (N_MELS + 1)
        val binWidth = SAMPLE_RATE.toDouble() / N_FFT
        return Array(N_MELS) { b ->
            val left = melLow + b * delta
            val center = left + delta
            val right = center + delta
            DoubleArray(N_FFT / 2) { k ->
                val m = mel(k * binWidth)
                when {
                    m <= left || m >= right -> 0.0
                    m <= center             -> (m - left) / (center - left)
                    else                    -> (right - m) / (right - center)
                }
            }
        }
    }

    /** In-place radix-2 Cooley-Tukey FFT over [re]/[im] (length N_FFT). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = N_FFT

        // Bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len
            val wrStep = cos(angle); val wiStep = -sin(angle)
            var pos = 0
            while (pos < n) {
                var wr = 1.0; var wi = 0.0
                for (i in 0 until len / 2) {
                    val ur = re[pos + i];     val ui = im[pos + i]
                    val vr = re[pos+i+len/2] * wr - im[pos+i+len/2] * wi
                    val vi = re[pos+i+len/2] * wi + im[pos+i+len/2] * wr
                    re[pos + i]       = ur + vr; im[pos + i]       = ui + vi
                    re[pos+i+len/2]   = ur - vr; im[pos+i+len/2]   = ui - vi
                    val nr = wr * wrStep - wi * wiStep; wi = wr * wiStep + wi * wrStep; wr = nr
                }
                pos += len
            }
            len = len shl 1
        }
    }

    /** Frames Kaldi produces for [samples] samples (snip_edges=true: no partial frames). */
    fun numFrames(samples: Int): Int =
        if (samples < FRAME_LENGTH) 0 else 1 + (samples - FRAME_LENGTH) / FRAME_SHIFT

    /**
     * Computes CMN'd log-mel features from raw PCM-16 audio (16 kHz mono).
     * Returns a flat [frames × N_MELS] FloatArray and the frame count,
     * ready to wrap in an ONNX tensor of shape [1, frames, N_MELS].
     */
    fun compute(audio: ShortArray): Pair<FloatArray, Int> {
        val filters   = MEL_WEIGHTS
        val numFrames = numFrames(audio.size)
        val out       = FloatArray(numFrames * N_MELS)
        val re        = DoubleArray(N_FFT)
        val im        = DoubleArray(N_FFT)

        for (f in 0 until numFrames) {
            val start = f * FRAME_SHIFT
            // Kaldi frame processing order: remove DC → pre-emphasis → window → zero-pad.
            var mean = 0.0
            for (i in 0 until FRAME_LENGTH) mean += audio[start + i]
            mean /= FRAME_LENGTH
            for (i in 0 until FRAME_LENGTH) re[i] = audio[start + i] - mean
            for (i in FRAME_LENGTH - 1 downTo 1) re[i] -= PREEMPHASIS * re[i - 1]
            re[0] -= PREEMPHASIS * re[0]
            for (i in 0 until FRAME_LENGTH) re[i] *= HAMMING[i]
            re.fill(0.0, FRAME_LENGTH, N_FFT)
            im.fill(0.0)
            fft(re, im)

            for (m in 0 until N_MELS) {
                val w = filters[m]
                var energy = 0.0
                for (k in w.indices) {
                    if (w[k] != 0.0) energy += w[k] * (re[k] * re[k] + im[k] * im[k])
                }
                out[f * N_MELS + m] = ln(max(energy, LOG_FLOOR)).toFloat()
            }
        }

        // CMN: subtract each mel bin's mean over the clip (WeSpeaker applies no variance norm).
        if (numFrames > 0) {
            for (m in 0 until N_MELS) {
                var sum = 0.0
                for (f in 0 until numFrames) sum += out[f * N_MELS + m]
                val avg = (sum / numFrames).toFloat()
                for (f in 0 until numFrames) out[f * N_MELS + m] -= avg
            }
        }
        return out to numFrames
    }
}
