package com.naomi.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.GregorianCalendar

/** The reply parser has to survive whatever the user's chosen model sends back. */
class CloudBrainTest {

    @Test
    fun plainJsonReply() {
        val r = CloudBrain.parseReply("""{"say": "Hey! All good here.", "action": null}""")
        assertEquals("Hey! All good here.", r.say)
        assertNull(r.action)
    }

    @Test
    fun actionWithFences() {
        val r = CloudBrain.parseReply("```json\n{\"say\": \"\", \"action\": {\"type\": \"set_timer\", \"seconds\": 300}}\n```")
        assertEquals("", r.say)
        assertEquals("set_timer", r.action!!.optString("type"))
        assertEquals(300, r.action!!.optInt("seconds"))
    }

    @Test
    fun chatterAroundJsonAndReasoningBlocks() {
        val r = CloudBrain.parseReply(
            "<think>They want a call. {not json}</think>Sure thing: {\"say\": \"Calling now.\", \"action\": {\"type\": \"call\", \"name\": \"Mom\"}}"
        )
        assertEquals("Calling now.", r.say)
        assertEquals("Mom", r.action!!.optString("name"))
    }

    @Test
    fun plainTextIsSpeech() {
        val r = CloudBrain.parseReply("Honestly? I'd go with the blue one.")
        assertEquals("Honestly? I'd go with the blue one.", r.say)
        assertNull(r.action)
    }

    @Test
    fun chatOrTypelessActionIsJustTalk() {
        assertNull(CloudBrain.parseReply("""{"say": "Hi", "action": {"type": "chat"}}""").action)
        assertNull(CloudBrain.parseReply("""{"say": "Hi", "action": {}}""").action)
    }

    @Test
    fun unrelatedJsonIsSpokenNotExecuted() {
        // A model that ignores the format and emits some other object shouldn't trigger anything.
        val r = CloudBrain.parseReply("""{"answer": 42}""")
        assertNull(r.action)
        assertEquals("""{"answer": 42}""", r.say)
    }

    @Test
    fun speechIsCleanedForTheVoice() {
        assertEquals("Ha, good one. Anyway!", CloudBrain.cleanSpeech("Naomi: \"Ha, *laughs* good one. Anyway!\""))
        assertEquals("Sure.", CloudBrain.cleanSpeech("<think>hmm</think> Sure."))
        assertEquals("An impasta. Hope that got a smile!", CloudBrain.cleanSpeech("An impasta. (laughs) Hope that got a smile!"))
    }

    @Test
    fun narrationMayDropDetailsButNeverChangeNumbers() {
        val fact = "Tomorrow in Sydney: mostly clear, with a high of 24 and a low of 15."
        assertTrue(CloudBrain.keepsNumbers(fact, "Sunny one tomorrow — 24 at the top, 15 overnight. Beach day?"))
        assertTrue("dropping a detail is fine", CloudBrain.keepsNumbers(fact, "24 degrees tomorrow, bring your sunnies."))
        assertFalse("an invented number isn't", CloudBrain.keepsNumbers(fact, "Lovely tomorrow, around 25 degrees!"))
        assertFalse("the headline number must survive", CloudBrain.keepsNumbers(fact, "Nice and mild tomorrow!"))
        assertTrue("numbers in words count",
            CloudBrain.keepsNumbers("Riverside is about 3.1 kilometres away by road, around 7 minutes by car.",
                "Just a hop away — about 3 point 1 kilometres, seven minutes in the car."))
        assertTrue("hyphenated numbers too", CloudBrain.keepsNumbers(fact, "A high of twenty-four and a low of fifteen."))
        assertTrue(CloudBrain.keepsNumbers("Flashlight on.", "Let there be light!"))
    }

    @Test
    fun distanceSentences() {
        assertEquals("Riverside is about 14 kilometres away by road, around 25 minutes by car.",
            DistanceClient.sentence("Riverside", 11.3, 14.2, 25))
        assertEquals("Oakwood is about 3.4 kilometres from you in a straight line.",
            DistanceClient.sentence("Oakwood", 3.43, null, null))
        assertEquals("Canberra is about 286 kilometres away by road, around 3 hours and 5 minutes by car.",
            DistanceClient.sentence("Canberra", 248.0, 286.0, 185))
    }

    @Test
    fun nearestPlacesAreSaidPlainly() {
        assertEquals("Iris Street opposite Patanga Road", DistanceClient.spelledOut("Iris St Opp Patanga Rd"))
        assertEquals("Mona Vale Road at Saint Ives Station", DistanceClient.spelledOut("Mona Vale Rd at St Ives Station"))
        // A chain store is known by the shopping centre it's in — not the lane behind the car park.
        assertEquals("the Woolworths at Forestway Shopping Centre",
            DistanceClient.spokenPlace("Woolworths", "Forestway Shopping Centre", null, "Sorlie Place", "woolworths"))
        assertEquals("the Woolworths in Belrose", DistanceClient.spokenPlace("Woolworths", null, "Belrose", "Glenrose Place", "Woolworths"))
        assertEquals("the bus stop on Forest Way", DistanceClient.spokenPlace("", null, null, "Forest Way", "the closest bus stop"))
        assertEquals("The nearest bus stop is Iris Street opposite Patanga Road, about 600 metres away, around an 8-minute walk.",
            DistanceClient.nearestSentence("bus stop", "Iris Street opposite Patanga Road", 0.616, 8, onFoot = true))
        assertEquals("The nearest Woolworths is the Woolworths on Glenrose Place, about 2.9 kilometres away by road, around 6 minutes by car.",
            DistanceClient.nearestSentence("Woolworths", "the Woolworths on Glenrose Place", 2.94, 6, onFoot = false))
        assertEquals("bus stop", DistanceClient.kindOf("the closest bus stop"))
    }

    @Test
    fun ramblingIsCutAtASentenceEnd() {
        val long = "Octopuses are clever. ".repeat(40)
        val spoken = CloudBrain.cleanSpeech(long)
        assertTrue(spoken.length <= 400)
        assertTrue(spoken.endsWith("clever."))
    }

    @Test
    fun sheKnowsWhenItIsNotTheOwner() {
        val guest = CloudBrain.systemPrompt("You are Naomi.", TurnContext(mapOf("name" to "Ozzy"), speaker = Who.GUEST), Date(0))
        assertTrue(guest.contains("talking with a guest"))
        assertTrue(guest.contains("Never call them Ozzy"))
        assertTrue(guest.contains("Don't share anything personal about Ozzy"))
        val owner = CloudBrain.chatPrompt("You are Naomi.", TurnContext(mapOf("name" to "Ozzy"), speaker = Who.OWNER), Date(0))
        assertTrue(owner.contains("You're talking with Ozzy"))
        assertFalse(owner.contains("guest"))
    }

    @Test
    fun chatPromptHasPersonaButNoActionsOrFormat() {
        val prompt = CloudBrain.chatPrompt("You are Naomi, dry-witted.", TurnContext(mapOf("name" to "Ozzy")), Date(0))
        assertTrue(prompt.startsWith("You are Naomi, dry-witted."))
        assertTrue(prompt.contains("The user's name is Ozzy."))
        assertFalse(prompt.contains("PHONE ACTIONS"))
        assertFalse(prompt.contains("\"say\""))
    }

    @Test
    fun systemPromptCarriesPersonaNameFactsAndFormat() {
        val prompt = CloudBrain.systemPrompt(
            persona = "You are Naomi, dry-witted.",
            ctx = TurnContext(mapOf("name" to "Ozzy", "mom" to "Amma")),
            now = Date(0)
        )
        assertTrue(prompt.startsWith("You are Naomi, dry-witted."))
        assertTrue(prompt.contains("The user's name is Ozzy."))
        assertTrue(prompt.contains("their mom is Amma"))
        assertFalse("name is listed as a name, not a remembered fact", prompt.contains("their name is Ozzy"))
        assertTrue(prompt.contains("\"say\""))
        assertTrue(prompt.contains("{\"type\":\"set_alarm\""))
        assertTrue(prompt.contains("{\"type\":\"remember\""))
        assertFalse("no memories, no memory section", prompt.contains("Things you remember"))
    }

    @Test
    fun recalledMemoriesReachEveryPrompt() {
        val ctx = TurnContext(
            mapOf("name" to "Ozzy"),
            memories = listOf("Ozzy is vegetarian", "Yesterday: Ozzy was nervous about his interview."),
        )
        for (prompt in listOf(
            CloudBrain.systemPrompt("You are Naomi.", ctx, Date(0)),
            CloudBrain.chatPrompt("You are Naomi.", ctx, Date(0)),
            CloudBrain.narrationPrompt("You are Naomi.", ctx, Date(0), "Battery is at 23 percent."),
        )) {
            assertTrue(prompt.contains("Things you remember"))
            assertTrue(prompt.contains("- Ozzy is vegetarian\n"))
            assertTrue(prompt.contains("- Yesterday: Ozzy was nervous about his interview."))
        }
    }

    @Test
    fun sheRepliesInTheLanguageSheSpeaks() {
        val ctx = TurnContext(mapOf("name" to "Ozzy"), language = Language.PORTUGUESE)
        for (prompt in listOf(
            CloudBrain.systemPrompt("You are Naomi.", ctx, Date(0)),
            CloudBrain.chatPrompt("You are Naomi.", ctx, Date(0)),
            CloudBrain.narrationPrompt("You are Naomi.", ctx, Date(0), "Battery is at 23 percent."),
            CloudBrain.answerPrompt("You are Naomi.", ctx, Date(0)),
        )) {
            assertTrue(prompt.contains("Always reply in Brazilian Portuguese"))
        }
        // English is said too: after a switch, the history can still be in Portuguese.
        assertTrue(CloudBrain.systemPrompt("You are Naomi.", TurnContext(mapOf("name" to "Ozzy")), Date(0))
            .contains("Always reply in English, even if earlier messages were in another language"))
    }

    @Test
    fun theRecognizersOtherGuessesReachTheBrain() {
        val ctx = TurnContext(mapOf("name" to "Ozzy"), heardAs = listOf("how can I get to the closest Woolworths by bus"))
        val prompt = CloudBrain.systemPrompt("You are Naomi.", ctx, Date(0))
        assertTrue(prompt.contains("Its other guesses: \"how can I get to the closest Woolworths by bus\""))
        assertFalse(CloudBrain.systemPrompt("You are Naomi.", TurnContext(mapOf("name" to "Ozzy")), Date(0)).contains("other guesses"))
    }

    @Test
    fun extractedMemories() {
        assertEquals(
            listOf("sister's name" to "Ozzy's sister is called Ana", "home city" to "Ozzy lives in Sydney"),
            CloudBrain.parseMemories("""{"memories": [{"topic": "sister's name", "fact": "Ozzy's sister is called Ana"},
                {"topic": "home city", "fact": " Ozzy lives in Sydney "}]}""")
        )
        assertEquals(emptyList<Pair<String, String>>(), CloudBrain.parseMemories("""{"memories": []}"""))
        assertEquals("chatter and reasoning around the JSON", 1,
            CloudBrain.parseMemories("<think>hmm</think>Sure! {\"memories\":[{\"topic\":\"diet\",\"fact\":\"Ozzy is vegetarian\"}]}").size)
        assertEquals("too short to be a fact", 0, CloudBrain.parseMemories("""{"memories":[{"topic":"x","fact":"ok"}]}""").size)
        assertEquals("a bare answer isn't a fact", 0, CloudBrain.parseMemories("""{"memories":[{"topic":"home city","fact":"Melbourne"}]}""").size)
        assertEquals("at most three at a time", 3, CloudBrain.parseMemories(
            """{"memories":[""" + (1..5).joinToString(",") { """{"topic":"t$it","fact":"Ozzy has fact number $it"}""" } + "]}"
        ).size)
        assertEquals(0, CloudBrain.parseMemories("I don't see anything worth remembering.").size)
    }

    @Test
    fun learnedFactsMustComeFromWhatWasSaid() {
        assertTrue(CloudBrain.grounded("Ozzy is vegetarian", "I've stopped eating meat, I'm vegetarian now", "Ozzy"))
        assertTrue(CloudBrain.grounded("Ozzy's dog is called Biscuit", "What's your dog called? Biscuit", "Ozzy"))
        assertFalse("nothing in common", CloudBrain.grounded("Ozzy feels fatigued", "ugh I'm so tired today", "Ozzy"))
        assertFalse("copied from the prompt's examples",
            CloudBrain.grounded("Ozzy has a brother called Tom", "my brother is driving me nuts", "Ozzy"))
        assertTrue("unless it was really said", CloudBrain.grounded("Ozzy has a brother called Tom", "my brother Tom is visiting", "Ozzy"))
    }

    @Test
    fun memoryPromptShowsWhatIsKnownAndTheDate() {
        val prompt = CloudBrain.memoryPrompt("Ozzy", listOf("[home city] Ozzy lives in Sydney"), GregorianCalendar(2026, 8, 29).time)
        assertTrue(prompt.contains("Today is Tuesday 29 September 2026."))
        assertTrue(prompt.contains("- [home city] Ozzy lives in Sydney"))
        assertFalse(CloudBrain.memoryPrompt("Ozzy", emptyList(), Date(0)).contains("Already known"))
    }
}
