package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Which language Naomi speaks for each choice in Settings. */
class LanguageTest {

    @Test
    fun automaticFollowsThePhone() {
        assertEquals(Language.PORTUGUESE, Language.resolve(Language.AUTO, Locale.forLanguageTag("pt-BR")))
        assertEquals(Language.PORTUGUESE, Language.resolve(Language.AUTO, Locale.forLanguageTag("pt-PT")))
        assertEquals(Language.ENGLISH, Language.resolve(Language.AUTO, Locale.forLanguageTag("en-AU")))
        assertEquals(Language.ENGLISH, Language.resolve(Language.AUTO, Locale.forLanguageTag("es-ES")))
    }

    @Test
    fun aChosenLanguageWinsOverThePhone() {
        assertEquals(Language.ENGLISH, Language.resolve(Language.ENGLISH, Locale.forLanguageTag("pt-BR")))
        assertEquals(Language.PORTUGUESE, Language.resolve(Language.PORTUGUESE, Locale.forLanguageTag("en-US")))
    }
}
