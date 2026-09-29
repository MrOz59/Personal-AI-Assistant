package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * FBankExtractor must reproduce WeSpeaker's front-end (within float noise), or the voiceprints
 * come out wrong without any visible error. The fixtures are a 2 s synthetic signal (tones +
 * noise + 0.2 s of digital silence) and its CMN'd features from kaldi-native-fbank configured
 * as WeSpeaker does: hamming window, dither 0, 80 bins, 20 Hz to Nyquist, int16-range input.
 */
class FBankExtractorTest {

    private fun resource(name: String): ByteBuffer =
        ByteBuffer.wrap(javaClass.getResourceAsStream("/fbank/$name")!!.readBytes())
            .order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun matchesKaldiNativeFbank() {
        val pcmBuf = resource("signal_s16le.raw").asShortBuffer()
        val pcm = ShortArray(pcmBuf.remaining()).also { pcmBuf.get(it) }
        val expBuf = resource("expected_cmn_f32le.raw").asFloatBuffer()
        val expected = FloatArray(expBuf.remaining()).also { expBuf.get(it) }

        val (feats, frames) = FBankExtractor.compute(pcm)

        assertEquals(expected.size / FBankExtractor.N_MELS, frames)
        var worst = 0f
        for (i in expected.indices) worst = maxOf(worst, abs(feats[i] - expected[i]))
        assertTrue("max |kotlin - kaldi| = $worst", worst < 2e-3f)
    }

    @Test
    fun framesFollowKaldiSnipEdges() {
        assertEquals(0, FBankExtractor.compute(ShortArray(399)).second)
        assertEquals(1, FBankExtractor.compute(ShortArray(400)).second)
        assertEquals(98, FBankExtractor.numFrames(16000))
    }
}
