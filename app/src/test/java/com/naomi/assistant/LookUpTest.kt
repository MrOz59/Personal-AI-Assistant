package com.naomi.assistant

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Looking a question up: when she reads the pages, how many times she asks the brain, what she
 * says when nothing answers — and telling which questions need a search, in both languages.
 */
class LookUpTest {

    private val results = (1..5).map { SearchClient.Result("Result $it", "Snippet $it", "site$it.com", url = "https://site$it.com/") }
    private val excerpt = PageReader.Excerpt("site1.com", "Page", "Vitor Roque marcou aos 38 do segundo tempo.")

    private fun attempt(verdict: CloudBrain.Verdict, say: String? = null) =
        CloudBrain.Attempt(say?.let { CloudBrain.Answer(it, verdict == CloudBrain.Verdict.ANSWERED) }, verdict)

    /** A look-up whose brain gives [verdicts] in turn, recording each call; pages come back as [pages] after [readMs]. */
    private class Rig(
        val verdicts: List<CloudBrain.Attempt>,
        val pages: List<PageReader.Excerpt> = emptyList(),
        val readMs: Long = 0,
        val found: SearchClient.Found? = null,
    ) {
        val calls = mutableListOf<Triple<Int, Int, Boolean>>() // results, pages, creative
        var pagesRead = false
        val lookUp = LookUp(
            search = { found },
            read = { _, _ ->
                delay(readMs)
                pagesRead = true
                pages
            },
            answer = { results, pages, creative ->
                calls += Triple(results.size, pages.size, creative)
                verdicts[calls.size - 1]
            },
        )
    }

    private fun rig(vararg verdicts: CloudBrain.Attempt, pages: List<PageReader.Excerpt> = emptyList(), readMs: Long = 0) =
        Rig(verdicts.toList(), pages, readMs, SearchClient.Found(results, "SearXNG"))

    @Test
    fun answeredFromTheSnippetsLeavesThePagesUnread() {
        val rig = rig(attempt(CloudBrain.Verdict.ANSWERED, "Palmeiras won, 2 to 1."), pages = listOf(excerpt), readMs = 5_000)
        val outcome = runBlocking { rig.lookUp.run("palmeiras santos", "who won?") }
        assertEquals(LookUp.Outcome.Answered("Palmeiras won, 2 to 1.", fromPages = false), outcome)
        assertEquals(listOf(Triple(5, 0, true)), rig.calls)
        // Not waited for: the answer doesn't wait on pages it doesn't need.
        assertFalse(rig.pagesRead)
    }

    @Test
    fun whenTheSnippetsDontSayThePagesAreRead() {
        val rig = rig(attempt(CloudBrain.Verdict.NOT_THERE, "The results don't say who scored."),
            attempt(CloudBrain.Verdict.ANSWERED, "Vitor Roque, in the 83rd minute."), pages = listOf(excerpt))
        val outcome = runBlocking { rig.lookUp.run("palmeiras santos gol", "who scored?") }
        assertEquals(LookUp.Outcome.Answered("Vitor Roque, in the 83rd minute.", fromPages = true), outcome)
        // Snippets first, lively; then the pages with the top snippets, steadier.
        assertEquals(listOf(Triple(5, 0, true), Triple(LookUp.SNIPPETS_WITH_PAGES, 1, false)), rig.calls)
    }

    @Test
    fun nothingToReadMeansHerOwnLineAboutIt() {
        val rig = rig(attempt(CloudBrain.Verdict.NOT_THERE, "The results don't say who scored."))
        val outcome = runBlocking { rig.lookUp.run("q", "who scored?") }
        assertEquals(LookUp.Outcome.NotAnswered("The results don't say who scored."), outcome)
        assertEquals(1, rig.calls.size)
    }

    @Test
    fun anAnswerThatStrayedGetsOneSteadierTry() {
        val rig = rig(attempt(CloudBrain.Verdict.UNSURE), attempt(CloudBrain.Verdict.ANSWERED, "It's 5.42 reais."))
        assertEquals(LookUp.Outcome.Answered("It's 5.42 reais.", fromPages = false), runBlocking { rig.lookUp.run("q", "dólar?") })
        assertEquals(listOf(Triple(5, 0, true), Triple(5, 0, false)), rig.calls)
    }

    @Test
    fun neverMoreThanTwoCallsToTheBrain() {
        val rig = rig(attempt(CloudBrain.Verdict.UNSURE), attempt(CloudBrain.Verdict.UNSURE), pages = listOf(excerpt))
        assertEquals(LookUp.Outcome.NotAnswered(null), runBlocking { rig.lookUp.run("q", "who scored?") })
        assertEquals(2, rig.calls.size)
        // The pages didn't say either: what she said about the snippets stands.
        val notThere = rig(attempt(CloudBrain.Verdict.NOT_THERE, "No word on the scorer."), attempt(CloudBrain.Verdict.NOT_THERE, "Still nothing."),
            pages = listOf(excerpt))
        assertEquals(LookUp.Outcome.NotAnswered("Still nothing."), runBlocking { notThere.lookUp.run("q", "who scored?") })
    }

    @Test
    fun anUnreachableBrainOrNoResultsEndItThere() {
        val rig = rig(attempt(CloudBrain.Verdict.UNREACHABLE), pages = listOf(excerpt), readMs = 5_000)
        assertEquals(LookUp.Outcome.NotAnswered(null), runBlocking { rig.lookUp.run("q", "who won?") })
        assertEquals(1, rig.calls.size)
        assertFalse(rig.pagesRead)
        val nothing = Rig(emptyList())
        assertEquals(LookUp.Outcome.NoResults, runBlocking { nothing.lookUp.run("q", "who won?") })
        assertTrue(nothing.calls.isEmpty())
    }

    /** A brain that answers every call with [reply] (or fails), counting them. */
    private class FakeBrain(val reply: String?, override val small: Boolean = true) : LlmClient {
        var calls = 0
        var lastTurns: List<Turn> = emptyList()
        override fun complete(system: String, turns: List<Turn>, schema: String?, creative: Boolean): String {
            calls++
            lastTurns = turns
            return reply ?: throw LlmException("offline")
        }
    }

    @Test
    fun anAnswerMayStateWhatThePagesSay() {
        val snippets = listOf(SearchClient.Result("Palmeiras vence o Santos", "Palmeiras venceu por 2 a 1.", "ge.globo.com"))
        val scored = """{"found": true, "answer": "Vitor Roque marcou aos 38 do segundo tempo.", "comment": ""}"""
        fun attempt(reply: String?, pages: List<PageReader.Excerpt> = emptyList()) = runBlocking {
            CloudBrain(FakeBrain(reply), "persona").tryAnswer("quem fez o gol?", "gol palmeiras", snippets, emptyList(), TurnContext(), pages)
        }
        // "38" is only on the page: without it, a made-up number; with it, the answer.
        assertEquals(CloudBrain.Verdict.UNSURE, attempt(scored).verdict)
        assertEquals(CloudBrain.Verdict.ANSWERED, attempt(scored, listOf(excerpt)).verdict)
        assertEquals(CloudBrain.Verdict.NOT_THERE,
            attempt("""{"found": false, "answer": "Os resultados não dizem.", "comment": ""}""").verdict)
        assertEquals(CloudBrain.Verdict.UNREACHABLE, attempt(null).verdict)
    }

    @Test
    fun thePagesComeWithTheResults() {
        val turn = CloudBrain.searchTurn("quem fez o gol?", "gol palmeiras",
            listOf(SearchClient.Result("Palmeiras vence", "Venceu por 2 a 1.", "ge.globo.com")), Date(0),
            listOf(PageReader.Excerpt("uol.com.br", "Palmeiras 2 x 1 Santos", "Vitor Roque marcou aos 38.", "2026-09-28")))
        assertTrue(turn, turn.contains("1. Palmeiras vence (ge.globo.com): Venceu por 2 a 1.\n\nFrom the pages:\n" +
            "Palmeiras 2 x 1 Santos (uol.com.br, 2026-09-28):\nVitor Roque marcou aos 38.\n\nMy question: quem fez o gol?"))
    }

    @Test
    fun aQuestionGoingToASearchSkipsTheChatPass() {
        val brain = FakeBrain("""{"say": "Hmm, I think Palmeiras.", "action": null}""")
        runBlocking { CloudBrain(brain, "persona").respond("who won the game?", emptyList(), TurnContext(), chat = false) }
        assertEquals(1, brain.calls)
        runBlocking { CloudBrain(brain, "persona").respond("how are you?", emptyList(), TurnContext()) }
        assertEquals(3, brain.calls)
    }

    @Test
    fun questionsOnlyASearchCanAnswer() {
        for (said in listOf("who won the game last night?", "Naomi, what's the latest news", "google the cricket score",
                "quem ganhou o jogo ontem?", "Naomi, qual o placar do jogo do Palmeiras?", "quanto tá o dólar hoje?",
                "qual o preço da gasolina", "que horas abre o mercado hoje", "o mercado abre hoje?",
                "quando estreia o novo filme do Batman?", "quais são as últimas notícias", "pesquisa o horário do jogo",
                "você pode pesquisar na internet sobre o eclipse", "dá um google no resultado da mega-sena",
                // How Brazilians ask about a game, a shop's hours or the news, with or without the question mark.
                "quanto foi o jogo do Flamengo ontem", "o Flamengo ganhou ontem?", "tem jogo do Flamengo hoje",
                "que horas começa o jogo", "até que horas funciona o Carrefour hoje", "até que horas o mercado abre?",
                "como tá o jogo do Palmeiras", "qual a tabela do Brasileirão", "busca na internet o horário do jogo",
                "o que saiu de notícia hoje", "tem alguma notícia sobre a greve?", "qual o resultado do Palmeiras",
                "sabe quem ganhou o jogo ontem", "já saiu o resultado da Mega-Sena", "tá aberto o mercado agora",
                "tem notícia do Flamengo", "e o dólar hoje", "tá quanto o dólar hoje", "tem jogo hoje", "tem jogo hoje?",
                "quando joga o Palmeiras", "que horas joga o Brasil hoje", "quanto ficou o jogo", "quanto que tá o dólar",
                "o dólar tá quanto", "o mercado fecha que horas",
                // An outright request to search, however it's said.
                "google the cricket score!", "pesquisa o preço do dólar!")) {
            assertTrue(said, AssistantBrain.looksLive(said))
        }
        for (said in listOf("how are you?", "what's your name", "quem é você?", "qual é a capital da França?",
                "me lembra de comprar pão", "toca música", "como você está?", "que dia é hoje?",
                // Exclamations and remarks about the news aren't questions about it.
                "que notícia boa", "Que notícia boa!", "que ótima notícia", "sabe que o preço da gasolina subiu de novo",
                "já te falei que o preço do aluguel subiu", "Posso te contar uma notícia?", "Tem uma notícia pra você",
                // Nothing current to look up: her memory, a sum, a car, the phone, the calendar.
                "quais as últimas coisas que eu te contei", "quais foram as últimas mensagens da Maria",
                "qual o resultado de 25 vezes 4", "qual o resultado do meu exame", "como funciona o câmbio automático",
                "qual a tabela do 7", "quanto tá a bateria", "quando começa minha reunião",
                // "Buscar" is as often picking someone up; "tá bom" is OK, not a question.
                "buscar minha mãe no aeroporto", "busca a música Evidências", "tá bom abre o Spotify agora",
                // English: a question mark alone doesn't make "news" a question about the news.
                "can you open the news app?", "Want to hear my news?", "text John are you watching the news?",
                // Her memory and the owner's own plans, which only mention a game.
                "o que eu te falei sobre o jogo do Palmeiras", "onde eu deixei o ingresso do jogo do Palmeiras",
                "quem vai comigo no jogo do Palmeiras sábado", "que horas eu marquei pra ver o jogo do Flamengo",
                "quanto eu gastei no jogo do Palmeiras", "me conta uma piada sobre o jogo do Palmeiras",
                // Reminders, alarms and messages about a game or the news.
                "quando eu chegar em casa me lembra de ver o jogo do Palmeiras", "quando eu acordar me lembra de ver as notícias",
                "quando der 8 horas me lembra de ver o placar", "você pode me lembrar do jogo do Palmeiras amanhã?",
                "tem como colocar um alarme pro jogo do Brasil?", "cria um lembrete pro jogo do Brasil?",
                "pergunta pro João se ele vai no jogo do Palmeiras?", "manda pra mãe que horas o mercado fecha?",
                // The phone and the calendar.
                "quanto tá sua bateria", "quanto tá o seu volume", "quanto tá faltando pro timer acabar", "quanto tá a internet",
                "quando começa a reunião de amanhã", "quando sai meu salário", "quando começa as minhas férias",
                // Her take on news she just gave.
                "o que você acha dessa notícia", "quem te deu essa notícia", "quando você vai me dar uma notícia boa")) {
            assertFalse(said, AssistantBrain.looksLive(said))
        }
    }

    @Test
    fun whatToSearchFor() {
        assertEquals("the cricket score", AssistantBrain.searchQuery("google the cricket score"))
        assertEquals("o horário do jogo", AssistantBrain.searchQuery("pesquisa o horário do jogo"))
        assertEquals("o eclipse", AssistantBrain.searchQuery("você pode pesquisar na internet sobre o eclipse"))
        assertEquals("resultado da mega-sena", AssistantBrain.searchQuery("dá um google no resultado da mega-sena"))
        assertEquals("quem ganhou o jogo do Flamengo", AssistantBrain.searchQuery("pesquisa na internet quem ganhou o jogo do Flamengo"))
        assertEquals("o preço do iPhone", AssistantBrain.searchQuery("busca no google o preço do iPhone"))
        // The question itself, without her name.
        assertEquals("quem ganhou o jogo ontem", AssistantBrain.searchQuery("Naomi, quem ganhou o jogo ontem?"))
    }

    @Test
    fun searchingOnTheBrainsPc() {
        assertEquals("http://pc.tail1234.ts.net:8888", BrainSettings.pcSearch("http://pc.tail1234.ts.net:11434/v1"))
        assertEquals("http://desktop.tailnet-abc.ts.net:8888", BrainSettings.pcSearch(" https://desktop.tailnet-abc.ts.net/v1 "))
        assertEquals("", BrainSettings.pcSearch("http://192.168.0.10:11434/v1"))
        assertEquals("", BrainSettings.pcSearch("https://openrouter.ai/api/v1"))
        assertEquals("", BrainSettings.pcSearch(""))
    }

    @Test
    fun searchResultsKeepWhereTheyLead() {
        val searxng = SearchClient.fromSearxng(JSONObject("""
            {"answers": [{"answer": "R$ 5,42", "url": "https://www.bcb.gov.br/cotacoes"}],
             "infoboxes": [{"infobox": "Dólar", "content": "Moeda dos Estados Unidos.", "id": "https://pt.wikipedia.org/wiki/D%C3%B3lar", "engine": "wikipedia"}],
             "results": [{"title": "Dólar hoje", "content": "Cotação do dólar.", "url": "https://economia.uol.com.br/cotacoes/"}]}
        """.trimIndent()))
        assertEquals(listOf("https://www.bcb.gov.br/cotacoes", "https://pt.wikipedia.org/wiki/D%C3%B3lar", "https://economia.uol.com.br/cotacoes/"),
            searxng.map { it.url })
        assertEquals("pt.wikipedia.org", searxng[1].site)
        // An infobox with no address still says whose it is.
        assertEquals("Wikidata", SearchClient.fromSearxng(JSONObject(
            """{"infoboxes": [{"infobox": "X", "content": "Y", "engine": "wikidata"}]}""")).single().site)

        val duck = SearchClient.fromDuckDuckGo("""
            <div class="result results_links web-result "><h2 class="result__title">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fge.globo.com%2Fjogo%3Fa%3D1&amp;rut=abc">Jogo</a></h2>
              <a class="result__url" href="x">ge.globo.com/jogo</a>
              <a class="result__snippet" href="x">Palmeiras venceu.</a></div>
        """.trimIndent())
        assertEquals("https://ge.globo.com/jogo?a=1", duck.single().url)
        assertEquals("https://example.com/a", SearchClient.duckTarget("https://example.com/a"))
        assertEquals("", SearchClient.duckTarget("javascript:void(0)"))
        assertEquals("https://en.wikipedia.org/wiki/Dollar", SearchClient.fromInstantAnswers(JSONObject(
            """{"AbstractText": "The dollar.", "Heading": "Dollar", "AbstractSource": "Wikipedia", "AbstractURL": "https://en.wikipedia.org/wiki/Dollar"}""")).single().url)
    }

    @Test
    fun emojiEntitiesSurviveTheSnippet() {
        assertEquals("Gol! 😀", SearchClient.clean("Gol! &#x1F600;"))
        assertEquals("Gol! 😀", SearchClient.clean("Gol! &#128512;"))
    }
}
