package com.naomi.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SearchTest {

    @Test
    fun searxngResultsComeAfterItsAnswersAndInfobox() {
        val json = JSONObject("""
            {"query": "q",
             "answers": [{"answer": "26 &ndash; 22", "url": null}, "plain answer"],
             "infoboxes": [{"infobox": "Brisbane Broncos", "content": "An Australian <b>rugby league</b> club."}],
             "results": [
               {"title": "2025 NRL Grand Final - Wikipedia", "content": "The Broncos beat the Storm 26&#8211;22.",
                "url": "https://en.wikipedia.org/wiki/2025_NRL_Grand_Final", "publishedDate": "2025-10-05T00:00:00"},
               {"title": "No snippet", "content": "", "url": "https://example.com"},
               {"title": "Match centre", "content": "Full match replay", "url": "https://www.nrl.com/draw", "publishedDate": null}
             ]}
        """.trimIndent())
        val results = SearchClient.fromSearxng(json)
        assertEquals(listOf("Direct answer", "Direct answer", "Brisbane Broncos", "2025 NRL Grand Final - Wikipedia", "Match centre"),
            results.map { it.title })
        assertEquals("An Australian rugby league club.", results[2].snippet)
        assertEquals("The Broncos beat the Storm 26–22.", results[3].snippet)
        assertEquals("en.wikipedia.org", results[3].site)
        assertEquals("2025-10-05", results[3].published)
        assertEquals("nrl.com", results[4].site)
        assertNull(results[4].published)
    }

    @Test
    fun duckDuckGoPageSkipsAdsAndReadsResults() {
        val html = """
            <div class="result results_links results_links_deep result--ad ">
              <h2 class="result__title"><a rel="nofollow" class="result__a" href="//ad">Buy tickets</a></h2>
              <a class="result__snippet" href="//ad">Cheap seats</a>
            </div>
            <div class="result results_links results_links_deep web-result ">
              <div class="links_main links_deep result__body">
                <h2 class="result__title">
                  <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=x">2025 <b>NRL</b> Grand Final</a>
                </h2>
                <div class="result__extras"><div class="result__extras__url">
                  <a class="result__url" href="//duckduckgo.com/l/?uddg=x">
                    en.wikipedia.org/wiki/2025_NRL_Grand_Final
                  </a>
                </div></div>
                <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">The <b>Brisbane Broncos</b> won, 26&#x27;s the score.</a>
              </div>
            </div>
            <div class="result results_links results_links_deep web-result ">
              <h2 class="result__title"><a rel="nofollow" class="result__a" href="//y">No snippet here</a></h2>
            </div>
        """.trimIndent()
        val results = SearchClient.fromDuckDuckGo(html)
        assertEquals(1, results.size)
        assertEquals("2025 NRL Grand Final", results[0].title)
        assertEquals("The Brisbane Broncos won, 26's the score.", results[0].snippet)
        assertEquals("en.wikipedia.org", results[0].site)
    }

    @Test
    fun duckDuckGoCaptchaIsNoResults() {
        assertTrue(SearchClient.fromDuckDuckGo("""<div class="anomaly-modal__title">Unfortunately, bots use DuckDuckGo too.</div>""").isEmpty())
    }

    @Test
    fun instantAnswers() {
        val json = JSONObject("""{"Answer": "", "Heading": "Brisbane Broncos", "AbstractText": "A rugby league club.",
            "AbstractSource": "Wikipedia", "Definition": ""}""")
        assertEquals(listOf(SearchClient.Result("Brisbane Broncos", "A rugby league club.", "Wikipedia")), SearchClient.fromInstantAnswers(json))
        assertTrue(SearchClient.fromInstantAnswers(JSONObject("{}")).isEmpty())
    }

    @Test
    fun regionsAndSites() {
        assertEquals("au-en", SearchClient.duckRegion("en-AU"))
        assertEquals("wt-wt", SearchClient.duckRegion("en"))
        assertEquals("abc.net.au", SearchClient.siteOf("https://www.abc.net.au/news/story?x=1"))
        assertEquals("example.com", SearchClient.siteOf("  example.com/path "))
    }

    @Test
    fun longSnippetsAreCutAtAWord() {
        val cut = SearchClient.clean("word ".repeat(100))
        assertTrue(cut.length <= 301)
        assertTrue(cut.endsWith("word…"))
    }

    @Test
    fun answerMayOnlyStateNumbersTheResultsHave() {
        val source = "Broncos beat the Storm 26-22 on 5 Oct 2025. Bitcoin is trading at \$83,802.46 today."
        assertTrue(CloudBrain.statesOnly(source, "The Broncos won it, twenty-six to 22!"))
        assertTrue(CloudBrain.statesOnly(source, "That was on the 5th of October, 2025."))
        assertTrue(CloudBrain.statesOnly(source, "Bitcoin's at about 83,802 dollars."))
        assertTrue(CloudBrain.statesOnly(source, "Roughly 84 thousand."))
        assertTrue(CloudBrain.statesOnly(source, "It's the one everyone's talking about."))
        assertFalse(CloudBrain.statesOnly(source, "The Broncos won 30 to 22."))
        assertFalse(CloudBrain.statesOnly(source, "That was back in 2024."))
    }

    @Test
    fun parsesAnAnswer() {
        assertEquals(CloudBrain.Answer("The Broncos took it, 26 to 22. What a finish!", true),
            CloudBrain.parseAnswer("""{"found": true, "answer": "The Broncos took it, 26 to 22", "comment": "What a finish!"}"""))
        // A comment that brings facts of its own is dropped; so is one on an answer that wasn't found.
        assertEquals("The Broncos took it.",
            CloudBrain.parseAnswer("""{"found": true, "answer": "The Broncos took it.", "comment": "Their 7th title!"}""")?.say)
        assertEquals(CloudBrain.Answer("No luck there.", false),
            CloudBrain.parseAnswer("""{"found": false, "answer": "No luck there.", "comment": "Weird, right?"}"""))
        assertNull(CloudBrain.parseAnswer("""{"found": true, "answer": "", "comment": "Fun!"}"""))
        // A comment naming someone the answer doesn't — a model's guess at who else was involved.
        assertEquals("The Brisbane Broncos won. What a comeback, Ozzy!", CloudBrain.parseAnswer(
            """{"found": true, "answer": "The Brisbane Broncos won", "comment": "What a comeback, Ozzy!"}""", known = "who won? Ozzy")?.say)
        assertEquals("The Brisbane Broncos won.", CloudBrain.parseAnswer(
            """{"found": true, "answer": "The Brisbane Broncos won", "comment": "Tough year for the Roosters!"}""", known = "who won?")?.say)
        assertEquals("The Brisbane Broncos won. The Broncos' fans must be thrilled!", CloudBrain.parseAnswer(
            """{"found": true, "answer": "The Brisbane Broncos won", "comment": "The Broncos' fans must be thrilled!"}""")?.say)
        // A model that ignored the format still said something worth hearing.
        assertEquals(CloudBrain.Answer("The Broncos took it.", true), CloudBrain.parseAnswer("The Broncos took it."))
    }

    @Test
    fun placeholdersAndEmojiAreNotSaid() {
        assertNull(CloudBrain.parseAnswer("""{"found": true, "answer": "The next full moon is on October [insert date].", "comment": ""}"""))
        assertEquals("Bitcoin's at 83,802 dollars. Time to cash out!",
            CloudBrain.parseAnswer("""{"found": true, "answer": "Bitcoin's at 83,802 dollars \uD83D\uDE04", "comment": "Time to cash out! \uD83C\uDF15"}""")?.say)
    }

    @Test
    fun theBrainKnowsItCanLookThingsUp() {
        val prompt = CloudBrain.systemPrompt(BrainSettings.DEFAULT_PERSONA, TurnContext(), Date())
        assertTrue(prompt.contains("{\"type\":\"look_up\",\"query\":"))
        assertTrue(CloudBrain.REPLY_SCHEMA.contains("\"const\":\"look_up\""))
        val turn = CloudBrain.searchTurn("what's bitcoin worth?", "bitcoin price",
            listOf(SearchClient.Result("Bitcoin price", "Trading at \$83,802.", "coindesk.com", "2026-09-29")), Date(10 * 86_400_000L + 43_200_000L))
        assertTrue(turn.startsWith("Web results for \"bitcoin price\" (today is Sunday 11 January 1970; last year was 1969):"))
        assertTrue(turn.contains("1. Bitcoin price (coindesk.com, 2026-09-29): Trading at \$83,802."))
        assertTrue(turn.endsWith("My question: what's bitcoin worth?"))
    }
}
