package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Finding where the wake phrase ends in the mic buffer, from the audio alone. */
class SpeechLocatorTest {

    private val rate = 16000

    /** Background noise up to [noise] loud, with "speech" (a 300 Hz tone, [voice] loud) between [from] and [to] seconds. */
    private fun clip(seconds: Double, from: Double, to: Double, noise: Int = 60, voice: Int = 4000): ShortArray {
        var seed = 7L
        return ShortArray((seconds * rate).toInt()) { i ->
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val noise = (seed % (2 * noise + 1) - noise).toDouble()
            val t = i.toDouble() / rate
            val speech = if (t in from..to) voice * sin(2 * PI * 300 * t) else 0.0
            (noise + speech).toInt().toShort()
        }
    }

    @Test
    fun findsTheEndOfTheLastSpeech() {
        // "hey" at 0.3–0.5 s, "naomi" at 0.8–1.4 s, then the silence Vosk waits for.
        val audio = clip(3.0, 0.8, 1.4)
        for (i in (0.3 * rate).toInt() until (0.5 * rate).toInt()) audio[i] = (4000 * sin(2 * PI * 250 * i / rate)).toInt().toShort()
        val end = lastSpeechEnd(audio, rate)!!
        assertTrue("end at ${end / rate.toDouble()} s", end in (1.38 * rate).toInt()..(1.44 * rate).toInt())
    }

    @Test
    fun silenceOrFlatNoiseIsNotSpeech() {
        assertNull(lastSpeechEnd(clip(3.0, 5.0, 6.0), rate))
        assertNull(lastSpeechEnd(ShortArray(3 * rate), rate))
    }

    @Test
    fun trimKeepsOnlyTheVoice() {
        // 0.6 s of "speech" inside 3 s of room noise: about 0.6 s survives, plus a frame each side.
        val voice = trimToSpeech(clip(3.0, 0.8, 1.4), rate)
        assertTrue("kept ${voice.size / rate.toDouble()} s", voice.size in (0.58 * rate).toInt()..(0.70 * rate).toInt())
        assertEquals(0, trimToSpeech(clip(3.0, 5.0, 6.0), rate).size)
    }

    @Test
    fun aSoftlySpokenWordStillCounts() {
        // Peaking near 140 in a quiet room: well clear of the background, if quiet in absolute terms.
        val end = lastSpeechEnd(clip(3.0, 0.8, 1.4, noise = 10, voice = 200), rate)
        assertTrue("end at ${end?.let { it / rate.toDouble() }} s", end != null && end in (1.38 * rate).toInt()..(1.44 * rate).toInt())
    }

    @Test
    fun howFarTheVoiceStandsOutFromTheBackground() {
        val quiet = snrDb(clip(3.0, 0.8, 1.4), rate)!!
        val street = snrDb(clip(3.0, 0.8, 1.4, noise = 1500), rate)!!
        assertTrue("quiet room: $quiet dB", quiet > 30)
        assertTrue("street: $street dB", street < 15)
        assertNull(snrDb(ShortArray(1000), rate))
    }

    @Test
    fun tooShortToJudge() {
        assertEquals(null, lastSpeechEnd(ShortArray(1000), rate))
    }
}
