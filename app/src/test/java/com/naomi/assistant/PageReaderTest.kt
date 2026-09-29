package com.naomi.assistant

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** Reading the pages behind search results: their text, the passages about the question, and which pages to open. */
class PageReaderTest {

    private val gamePage = """
        <!DOCTYPE html><html lang="pt-BR"><head>
          <meta charset="utf-8">
          <title>Palmeiras vence o Santos no Allianz | ge</title>
          <meta name="description" content="Verd&atilde;o bate o rival por 2 a 1 e segue na lideran&ccedil;a do Brasileir&atilde;o.">
          <script>var gols = "Santos 5 x 0 Palmeiras";</script>
          <style>.placar { color: red }</style>
        </head><body>
          <header><nav><a href="/">Início</a> <a href="/futebol">Futebol: todas as notícias do seu time</a></nav></header>
          <!-- <p>Comentário escondido com placar 9 x 9 que não é o jogo.</p> -->
          <article class="teaser"><p>Leia também: Corinthians empata com o Grêmio na Arena.</p></article>
          <article class="materia">
            <h1>Palmeiras vence o Santos no Allianz Parque</h1>
            <p>O Palmeiras venceu o Santos por 2 a 1 na noite desta segunda-feira, no Allianz Parque, pela 27&ordf; rodada do Campeonato Brasileiro.</p>
            <p>Flaco L&oacute;pez abriu o placar aos 12 minutos do primeiro tempo. Guilherme empatou para o Santos no come&ccedil;o da etapa final.</p>
            <p>Aos 38 do segundo tempo, Vitor Roque marcou o gol da vit&oacute;ria alviverde, de cabe&ccedil;a, ap&oacute;s cruzamento.</p>
            <p>Com o resultado, o time de Abel Ferreira chegou a 58 pontos e segue na lideran&ccedil;a do campeonato.</p>
            <table><tr><th>Time</th><th>Pontos</th></tr><tr><td>Palmeiras</td><td>58</td></tr><tr><td>Flamengo</td><td>55</td></tr></table>
            <p>Ingressos para o pr&oacute;ximo jogo custam a partir de &#8364; 20, ou &#x1F600; nenhum.</p>
          </article>
          <aside><p>Mais lidas: Santos 5 x 0 Palmeiras relembra goleada de 1990 em amistoso histórico.</p></aside>
          <footer><p>© Globo Comunicação e Participações S.A. Todos os direitos reservados.</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun aPageIsItsArticlesText() {
        val page = PageReader.extract(gamePage)
        assertEquals("Palmeiras vence o Santos no Allianz | ge", page.title)
        assertEquals("Verdão bate o rival por 2 a 1 e segue na liderança do Brasileirão.", page.description)
        val text = page.blocks.joinToString("\n")
        assertTrue(text, text.contains("O Palmeiras venceu o Santos por 2 a 1 na noite desta segunda-feira"))
        assertTrue(text, text.contains("27ª rodada"))
        assertTrue(text, text.contains("Flaco López abriu o placar"))
        // Table rows, cells kept apart.
        assertTrue(text, text.contains("Palmeiras | 58"))
        // Numbered entities, even ones that take two chars.
        assertTrue(text, text.contains("€ 20, ou 😀 nenhum"))
        // Scripts, styles, comments, menus, teasers, sidebars and footers are no part of it.
        for (noise in listOf("5 x 0", "9 x 9", "color: red", "todas as notícias", "Corinthians", "direitos reservados")) {
            assertFalse(noise, text.contains(noise))
        }
    }

    @Test
    fun withoutAnArticleItsTheWholeBody() {
        val page = PageReader.extract("""
            <html><body><div class="main"><p>O dólar comercial fechou em alta de 0,8%, cotado a R$ 5,42 nesta terça.</p>
            <div>Curto</div><p>O Ibovespa recuou 1,1% no mesmo dia, pressionado pelas ações de bancos.</p></div></body></html>
        """.trimIndent())
        assertEquals(listOf("O dólar comercial fechou em alta de 0,8%, cotado a R$ 5,42 nesta terça.",
            "O Ibovespa recuou 1,1% no mesmo dia, pressionado pelas ações de bancos."), page.blocks)
    }

    @Test
    fun linesAsThePageMeansThem() {
        // Markup wrapped over several lines, as generated sites write it: still one paragraph.
        val wrapped = PageReader.extract("<p>O ingresso para o próximo jogo custa\n <a\n href=\"https://loja.example.com/ingressos?utm=1\"\n" +
            " class=\"link\">R$ 60</a> no setor norte.</p>")
        assertEquals(listOf("O ingresso para o próximo jogo custa R$ 60 no setor norte."), wrapped.blocks)
        // A page that sits in one big form (older ASP.NET sites) keeps its text; a search form doesn't.
        val inForm = PageReader.extract("<html><body><form id=\"form1\" action=\"x\"><div>" +
            (1..8).joinToString("") { "<p>O posto de saúde $it do bairro funciona das 7h às 19h de segunda a sexta-feira.</p>" } +
            "</div></form><form><input name=q><p>Busque no site por notícias e serviços da prefeitura.</p></form></body></html>")
        assertEquals(8, inForm.blocks.size)
        // Tags with a namespace, as Word and old social buttons write them, are tags too.
        assertEquals(listOf("A Prefeitura de Campinas informa que a vacinação foi prorrogada até 30 de outubro."),
            PageReader.extract("<p class=MsoNormal>A Prefeitura de <st1:PersonName w:st=\"on\">Campinas</st1:PersonName> informa que " +
                "a vacinação foi prorrogada até <st1:date Year=\"2026\">30 de outubro</st1:date>.<o:p></o:p></p>" +
                "<div><fb:like href=\"https://exemplo.com.br/palmeiras-vence-santos\"></fb:like></div>").blocks)
        // "a < b" is text, not a tag.
        assertEquals(listOf("Se a < b e b < c, então a é menor que c."), PageReader.extract("<p>Se a &lt; b e b < c, então a é menor que c.</p>").blocks)
    }

    @Test
    fun pagesBuiltToTripItUpAreStillQuick() {
        val size = PageReader.MAX_BYTES
        fun repeated(s: String) = buildString { while (length < size) append(s) }
        for (html in listOf(repeated("<!-- "), repeated("<figure class=a>"), repeated("<article>x "), repeated("<button a=b "),
                repeated("<meta a=\"b\" "), repeated("<title>x "), repeated("<"), repeated("<p "), repeated("<form>") + "</form>",
                "<body><script>" + repeated("\"<figure a=b><\\/figure>\","))) {
            val started = System.currentTimeMillis()
            PageReader.passages(PageReader.extract(html), "quem venceu o jogo do Palmeiras")
            val took = System.currentTimeMillis() - started
            assertTrue("${html.take(20)}… took $took ms", took < 2_000)
        }
    }

    @Test
    fun thePassagesAboutTheQuestion() {
        val page = PageReader.extract(gamePage)
        val said = PageReader.passages(page, "quem fez o gol da vitória do Palmeiras contra o Santos?")
        assertTrue(said, said.contains("Vitor Roque marcou o gol da vitória"))
        assertTrue(said.length <= PageReader.MAX_EXCERPT)
        // Accents and plurals don't stand in the way: "preco"/"ingresso" find "Ingressos".
        assertTrue(PageReader.passages(page, "preco do ingresso").contains("Ingressos para o próximo jogo"))
        // A tight budget keeps the best one.
        val one = PageReader.passages(page, "gol da vitória Vitor Roque", maxChars = 200)
        assertTrue(one, one.length <= 200 && one.contains("Vitor Roque"))
        // Nothing on the page about it: nothing.
        assertEquals("", PageReader.passages(page, "previsão do tempo em Curitiba"))
        // Korean syllables come apart when accents are folded away; the passage is still cut where the answer is.
        val korean = PageReader.extract("<p>" + "방탄소년단의 새 앨범 소식이 전해졌다. ".repeat(20) + "BTS novo álbum sai em 20 de junho.</p>")
        val bts = PageReader.passages(korean, "quando sai o novo álbum do BTS?")
        assertTrue(bts, bts.endsWith("BTS novo álbum sai em 20 de junho."))
    }

    @Test
    fun whichPagesToOpen() {
        fun r(title: String, url: String) = SearchClient.Result(title, "snippet", SearchClient.siteOf(url), url = url)
        val results = listOf(
            r("Vídeo dos gols", "https://www.youtube.com/watch?v=abc"),
            r("Tabela em PDF", "https://cbf.com.br/tabela.pdf"),
            r("Palmeiras x Santos: placar", "http://insecure.example.com/jogo"),
            r("Agenda do Brasileirão", "https://ge.globo.com/agenda"),
            r("Palmeiras vence o Santos", "https://ge.globo.com/palmeiras-vence"),
            r("Palmeiras 2 x 1 Santos: veja como foi", "https://www.uol.com.br/esporte/jogo"),
            r("Direct answer", ""),
        )
        val picked = PageReader.pick(results, "placar Palmeiras x Santos")
        // Pages only, over https, one per site, the ones about the question first.
        assertEquals(listOf("https://ge.globo.com/palmeiras-vence", "https://www.uol.com.br/esporte/jogo"), picked.map { it.url })
        assertFalse(PageReader.readable("https://m.facebook.com/story"))
        assertFalse(PageReader.readable("not a url"))
        // A result never leads into the owner's own network.
        for (inside in listOf("https://pc.tail1234.ts.net/admin", "https://192.168.0.1/", "https://10.0.0.5/x", "https://100.101.102.103/",
                "https://172.20.1.1/", "https://localhost/", "https://127.0.0.1:8080/", "https://router.local/", "https://intranet/",
                "https://[::1]/")) {
            assertFalse(inside, PageReader.readable(inside))
        }
        assertTrue(PageReader.readable("https://172.32.0.1/page"))
        assertTrue(PageReader.readable("http://127.0.0.1:8080/page", allowLocal = true))
    }

    @Test
    fun textInTheCharsetItCameIn() {
        val latin = "<html><body><p>Previsão: calor e umidade em São Paulo.</p></body></html>"
        // Undeclared and not UTF-8: an older Brazilian site's Windows-1252.
        assertEquals(latin, PageReader.decode(latin.toByteArray(charset("windows-1252"))))
        val declared = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=iso-8859-1\"></head>ação</html>"
        assertEquals(declared, PageReader.decode(declared.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("ação ✓", PageReader.decode("ação ✓".toByteArray()))
        assertEquals("ação", PageReader.decode("ação".toByteArray(Charsets.ISO_8859_1), Charsets.ISO_8859_1))
    }

    @Test
    fun readsWhatArrivesInTime() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun serve(path: String, type: String, body: String, delayMs: Long = 0) = server.createContext(path) { ex ->
            Thread.sleep(delayMs)
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", type)
            runCatching { ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        }
        serve("/game", "text/html; charset=utf-8", gamePage)
        serve("/slow", "text/html", gamePage, delayMs = 3_000)
        serve("/file", "application/pdf", "%PDF-1.4 Vitor Roque marcou")
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        try {
            val port = server.address.port
            val question = "quem marcou o gol da vitória do Palmeiras"
            fun r(host: String, path: String) = SearchClient.Result("Palmeiras vence", "gol", host, url = "http://$host:$port$path")

            val started = System.currentTimeMillis()
            val reader = PageReader(allowLocal = true)
            // The slow page ranked first: the one that arrived in time is still read.
            val read = runBlocking { reader.read(listOf(r("localhost", "/slow"), r("127.0.0.1", "/game")), question, "pt-BR", 1_500) }
            val took = System.currentTimeMillis() - started
            assertEquals(1, read.size)
            assertEquals("Palmeiras vence o Santos no Allianz | ge", read[0].title)
            assertTrue(read[0].text, read[0].text.contains("Vitor Roque"))
            assertTrue("took $took ms", took < 2_800)

            // Not a web page: nothing read from it.
            assertTrue(runBlocking { reader.read(listOf(r("127.0.0.1", "/file")), question) }.isEmpty())
            // And for real, pages on this machine aren't read at all.
            assertTrue(runBlocking { PageReader().read(listOf(r("127.0.0.1", "/game")), question) }.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun askingForTheirLanguage() {
        assertEquals("pt-BR,pt;q=0.9,en;q=0.5", PageReader.acceptLanguage("pt-BR"))
        assertEquals("en-AU,en;q=0.9", PageReader.acceptLanguage("en-AU"))
    }
}
