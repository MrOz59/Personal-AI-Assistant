package com.naomi.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading "Naomi" out of Vosk's results — in a quiet room and in a street — and finding it in the audio. */
class WakeWordTest {

    @Test
    fun aLoneNaomiIsClean() {
        val hit = findWake(JSONObject("""{"text":"naomi","result":[{"conf":0.93,"start":0.42,"end":0.95,"word":"naomi"}]}"""))!!
        assertEquals(0.93, hit.conf, 1e-9)
        assertEquals(0.53, hit.duration, 1e-9)
        assertTrue(hit.clean)
        assertTrue(findWake(JSONObject(
            """{"text":"hey naomi","result":[{"conf":0.9,"start":0.1,"end":0.3,"word":"hey"},{"conf":0.91,"start":0.3,"end":0.8,"word":"naomi"}]}"""
        ))!!.clean)
    }

    @Test
    fun naomiAmidNoiseIsStillFound() {
        // Street noise comes back as [unk] around the word — it used to throw the whole wake away.
        val hit = findWake(JSONObject("""{"text":"[unk] naomi [unk]","result":[
            {"conf":0.61,"start":0.10,"end":0.40,"word":"[unk]"},
            {"conf":0.84,"start":0.60,"end":1.10,"word":"naomi"},
            {"conf":0.50,"start":1.30,"end":1.60,"word":"[unk]"}]}"""))!!
        assertEquals(0.84, hit.conf, 1e-9)
        assertEquals(0.60, hit.start, 1e-9)
        assertFalse(hit.clean)
    }

    @Test
    fun noNaomiNoWake() {
        assertNull(findWake(JSONObject("""{"text":"[unk]","result":[{"conf":0.7,"start":0.1,"end":0.5,"word":"[unk]"}]}""")))
        assertNull("no word details to judge it by", findWake(JSONObject("""{"text":"naomi"}""")))
    }

    @Test
    fun runningGuess() {
        assertTrue(mentionsWake("""{"partial" : "[unk] naomi"}"""))
        assertFalse(mentionsWake("""{"partial" : "[unk]"}"""))
        assertFalse(mentionsWake("""{"partial" : ""}"""))
        assertFalse(mentionsWake("not json"))
    }

    @Test
    fun theWordIsFoundInTheRecentAudio() {
        val rate = 16000
        val margin = 2400
        // The utterance began 2 s in; "naomi" is 0.5–1.0 s into it, so at 2.5–3.0 s overall.
        val hit = WakeHit(conf = 0.9, start = 0.5, end = 1.0, clean = true)
        assertEquals(37600 until 50400, wakeWindow(hit, utteranceStart = 32000, fed = 64000, available = 64000, margin = margin, sampleRate = rate))
        // Later on, with only the newest 4 s kept: the same word, nearer the start of what's kept.
        assertEquals(1600 until 14400, wakeWindow(hit, 32000, fed = 100000, available = 64000, margin = margin, sampleRate = rate))
        // Gone from what's kept.
        assertNull(wakeWindow(hit, 32000, fed = 110000, available = 64000, margin = margin, sampleRate = rate))
        // Closed right after the word: the margin after it is cut short, the word isn't.
        assertEquals(37600 until 48500, wakeWindow(hit, 32000, fed = 48500, available = 48500, margin = margin, sampleRate = rate))
        assertNull("no timing", wakeWindow(WakeHit(0.9, Double.NaN, Double.NaN, true), 0, 64000, 64000, margin, rate))
    }
}
