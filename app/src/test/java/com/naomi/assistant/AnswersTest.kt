package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Her questions ("Send it?", "Which one?") answered in English or Brazilian Portuguese. */
class AnswersTest {

    @Test
    fun yesAndNo() {
        for (yes in listOf("yes", "sure, send it", "sim", "sim, pode mandar", "manda", "beleza")) {
            assertTrue(yes, CommandRouter.isYes(yes))
        }
        for (no in listOf("no", "cancel", "não", "nao", "não, cancela", "cancelar")) {
            assertTrue(no, CommandRouter.isNo(no))
        }
        assertFalse(CommandRouter.isYes("simples"))
        assertFalse("\"para\" is also \"to\": not a no", CommandRouter.isNo("manda para ela"))
    }

    @Test
    fun changingTheMessageOrTheContact() {
        for (a in listOf("change the message", "muda a mensagem", "mudar mensagem", "troca o texto", "manda outra mensagem")) {
            assertTrue(a, CommandRouter.isChangeMessage(a))
        }
        for (a in listOf("wrong person", "muda o contato", "trocar de pessoa", "pessoa errada")) {
            assertTrue(a, CommandRouter.isChangeContact(a))
        }
        assertFalse(CommandRouter.isChangeMessage("manda por mensagem de texto"))
        assertFalse(CommandRouter.isChangeContact("manda pro contato da maria"))
    }

    @Test
    fun pickingFromAList() {
        assertEquals(1, CommandRouter.portugueseNumber("um"))
        assertEquals(2, CommandRouter.portugueseNumber("o dois"))
        assertEquals(3, CommandRouter.portugueseNumber("três."))
        assertNull(CommandRouter.portugueseNumber("maria"))
    }
}
