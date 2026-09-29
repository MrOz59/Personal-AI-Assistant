package com.naomi.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Reads the pages behind search results, for when their snippets don't hold the answer: opens the
 * likeliest one or two, and keeps the few passages that bear on the question — short enough for a
 * small model to answer from quickly. Plain Kotlin, no HTML library: it only has to find the
 * paragraphs that matter, not lay out the page. Everything happens on the phone; nothing is sent
 * anywhere but to the pages themselves.
 */
class PageReader(
    // Tests serve pages from this machine; for real, a result never leads to the owner's own network.
    private val allowLocal: Boolean = false,
) {

    // Every hop checked, redirects included, and every address a name resolves to: a page on the
    // web can't send the phone to the owner's router or PC.
    private val http: OkHttpClient = if (allowLocal) HTTP else HTTP.newBuilder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).filterNot(::privateAddress)
                .ifEmpty { throw UnknownHostException("$hostname is on a private network") }
        })
        .addNetworkInterceptor { chain ->
            val host = chain.request().url.host
            if (local(host.lowercase(Locale.ROOT))) throw IOException("won't follow a page to $host")
            chain.proceed(chain.request())
        }
        .build()

    /** What a page says about the question: a few of its passages, and where they're from. */
    data class Excerpt(val site: String, val title: String, val text: String, val published: String? = null)

    /** A page as text: its [title], its own summary ([description]) and its paragraphs and table rows, in order. */
    data class Page(val title: String, val description: String, val blocks: List<String>)

    /**
     * Passages about [question] from the best of [results] (see [pick]), read in parallel and
     * given up on after [deadlineMs] — whatever arrived by then is kept. Empty if nothing useful
     * could be read. Cancelling it cancels the downloads. [language] ("pt-BR", "en-AU") is asked
     * of the sites.
     */
    suspend fun read(
        results: List<SearchClient.Result>,
        question: String,
        language: String = "en",
        deadlineMs: Long = DEADLINE_MS,
    ): List<Excerpt> = coroutineScope {
        val started = System.nanoTime()
        val reading = pick(results, question, allowLocal).map { r ->
            async(Dispatchers.IO) {
                // One page that can't be read or made sense of is just one page fewer.
                try {
                    fetch(r.url, language)?.let { excerpt(extract(it), question, r) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("Naomi", "Couldn't read ${r.site}: ${e.message}")
                    null
                }
            }
        }
        withTimeoutOrNull(deadlineMs) { reading.forEach { it.join() } }
        val read = reading.filter { it.isCompleted && !it.isCancelled }.mapNotNull { it.await() }
        reading.forEach { it.cancel() }
        android.util.Log.i("Naomi", "Read ${read.size} of ${reading.size} pages in ${(System.nanoTime() - started) / 1_000_000} ms")
        read
    }

    /** The page at [url] as HTML text, or null if it isn't a web page that could be read. */
    private suspend fun fetch(url: String, language: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", BROWSER)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
            .header("Accept-Language", acceptLanguage(language))
            .build()
        val call = http.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) android.util.Log.w("Naomi", "Couldn't open ${SearchClient.siteOf(url)}: ${e.message}")
                    cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(runCatching { response.use(::htmlOf) }.getOrNull())
                }
            })
        }
    }

    /** A page's HTML, read up to [MAX_BYTES]; null for an error or anything but a web page (a PDF, a video). */
    private fun htmlOf(response: Response): String? {
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        val type = body.contentType()
        if (type != null && type.subtype != "html" && type.subtype != "xhtml+xml") return null
        val bytes = body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < MAX_BYTES) {
                val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        return decode(bytes, type?.charset())
    }

    companion object {
        // One or two pages: one when it has the answer, a second when it doesn't — and pages are slow.
        const val MAX_PAGES = 2
        // Waiting on pages past this costs more than it's worth in a spoken answer.
        const val DEADLINE_MS = 5_000L
        // What she reads of a page: the article is near the top; the rest is comments, links and scripts.
        const val MAX_BYTES = 600 * 1024
        // What she keeps of each page for the model: a few paragraphs.
        const val MAX_EXCERPT = 900
        // A passage longer than this is cut down to the part about the question.
        private const val MAX_PASSAGE = 450
        // The share of the question's words a page has to have somewhere to be about it.
        private const val PAGE_MATCH = 0.4

        // Sites that are nothing without JavaScript or an account, and file types that aren't pages.
        private val UNREADABLE = setOf("youtube.com", "youtu.be", "instagram.com", "facebook.com", "fb.com", "x.com",
            "twitter.com", "tiktok.com", "linkedin.com", "pinterest.com", "threads.net", "maps.google.com", "play.google.com",
            "apps.apple.com", "open.spotify.com", "reddit.com", "t.me", "whatsapp.com")
        private val NOT_PAGES = Regex("\\.(pdf|jpe?g|png|gif|webp|svg|mp4|mp3|m4a|zip|rar|apk|docx?|xlsx?|pptx?|csv)(\\?|#|$)",
            RegexOption.IGNORE_CASE)

        // A phone browser: plenty of sites turn away anything that doesn't look like one.
        private const val BROWSER = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/128.0.0.0 Mobile Safari/537.36"

        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(DEADLINE_MS + 1_000, TimeUnit.MILLISECONDS)
            .build()

        /** "pt-BR" → "pt-BR,pt;q=0.9,en;q=0.5": their language first, English after. */
        fun acceptLanguage(language: String): String {
            val base = language.substringBefore('-').lowercase(Locale.ROOT).ifBlank { "en" }
            return if (base == "en") "$language,en;q=0.9" else "$language,$base;q=0.9,en;q=0.5"
        }

        /**
         * The results worth opening for [question], best first, at most [MAX_PAGES]: real pages
         * (not videos, social media or files) on sites that can be read from the phone, one per
         * site, among the top few — the ones whose title and snippet share most with the question,
         * ties going to the higher-ranked.
         */
        fun pick(results: List<SearchClient.Result>, question: String, allowLocal: Boolean = false): List<SearchClient.Result> {
            val terms = termsOf(question).toSet()
            return results.take(6).withIndex()
                .filter { (_, r) -> readable(r.url, allowLocal) }
                .sortedWith(compareByDescending<IndexedValue<SearchClient.Result>> { (_, r) ->
                    termsOf("${r.title} ${r.snippet}").toSet().count { it in terms }
                }.thenBy { it.index })
                .map { it.value }
                .distinctBy { hostOf(it.url) }
                .take(MAX_PAGES)
        }

        /**
         * Whether [url] is a page on the web the phone can read: https, a public site that works
         * without JavaScript or an account, and not a file. A result never leads into the owner's
         * own network — this phone, the LAN, their Tailscale machines — unless [allowLocal] (tests).
         */
        fun readable(url: String, allowLocal: Boolean = false): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            val host = parsed.host.lowercase(Locale.ROOT)
            if (local(host)) return allowLocal
            if (!parsed.isHttps || NOT_PAGES.containsMatchIn(parsed.encodedPath)) return false
            return UNREADABLE.none { host == it || host.endsWith(".$it") }
        }

        // An address on this phone, the LAN or Tailscale (100.64.0.0/10).
        private fun privateAddress(a: InetAddress): Boolean {
            val b = a.address
            val cgnat = b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            val uniqueLocal = b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
            return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || cgnat || uniqueLocal
        }

        // This machine, a private or Tailscale address, or a name only the local network knows.
        private fun local(host: String): Boolean =
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".lan") ||
                host.endsWith(".home") || host.endsWith(".internal") || host.endsWith(".ts.net") || '.' !in host ||
                host.startsWith("[") || PRIVATE_IP.matches(host)

        private val PRIVATE_IP = Regex("(127|10|0)\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|169\\.254\\.\\d+\\.\\d+|" +
            "172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.\\d+\\.\\d+")

        private fun hostOf(url: String) = url.toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty()

        /**
         * [bytes] as text, in the charset the server named, else the one the page declares in a
         * meta tag, else UTF-8 — or Windows-1252 when it plainly isn't UTF-8 (older Brazilian sites).
         */
        fun decode(bytes: ByteArray, declared: Charset? = null): String {
            val charset = declared ?: metaCharset(bytes) ?: if (validUtf8(bytes)) Charsets.UTF_8 else WINDOWS_1252
            return String(bytes, charset)
        }

        private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

        private fun metaCharset(bytes: ByteArray): Charset? {
            val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            val name = Regex("<meta[^>]+charset\\s*=\\s*[\"']?\\s*([\\w.:-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
                ?: return null
            return runCatching { Charset.forName(name) }.getOrNull()
        }

        // Whether [bytes] read as UTF-8 (a multi-byte character cut off at the end is fine).
        private fun validUtf8(bytes: ByteArray): Boolean {
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                val extra = when {
                    b < 0x80 -> 0
                    b in 0xC2..0xDF -> 1
                    b in 0xE0..0xEF -> 2
                    b in 0xF0..0xF4 -> 3
                    else -> return false
                }
                for (k in 1..extra) {
                    if (i + k >= bytes.size) return true
                    if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
                }
                i += extra + 1
            }
            return true
        }

        /**
         * A web page's text: its title, its own summary, and its paragraphs, list items and table
         * rows (cells joined by " | "), in order, without scripts, menus, headers and footers.
         * The article, when the page marks one out, rather than everything around it.
         */
        fun extract(html: String): Page {
            val clean = withoutNoise(html)
            val title = decodeEntities(stripTags(elementsOf(clean, "title").firstOrNull().orEmpty()))
                .ifBlank { metaContent(clean, "og:title") }
            val description = metaContent(clean, "description").ifBlank { metaContent(clean, "og:description") }
            val body = openingTag(clean, "body", 0)?.let { (_, end) -> clean.substring(end) } ?: clean
            // The page's article (the longest, if it has several — the others are teasers for more).
            val article = sequenceOf("article", "main")
                .map { tag -> elementsOf(body, tag).maxByOrNull(::textSize) }
                .firstOrNull { it != null && textSize(it) >= MIN_ARTICLE }
            val blocks = textOf(article ?: body, breaks = true).split('\n')
                .map { decodeEntities(it).replace(SPACES, " ").trim().trim('|').trim() }
                .filter { it.length >= MIN_BLOCK || (it.contains('|') && it.any(Char::isDigit)) }
                .distinct()
            return Page(title.replace(SPACES, " ").trim(), description.replace(SPACES, " ").trim(), blocks)
        }

        // The passes over a page's HTML below each read it once, front to back: a page built to
        // trip up a pattern (thousands of tags never closed) can't keep her busy past its deadline.

        /**
         * [html] without comments or the elements that are never the answer ([DROPPED]) — except a
         * form the whole page sits in, as on older ASP.NET sites. An element never closed is left
         * in, unless it's code (a script cut off at [MAX_BYTES]), which runs to the end.
         */
        private fun withoutNoise(html: String): String {
            val out = StringBuilder(html.length)
            val neverClosed = mutableSetOf<String>()
            val closers = mutableMapOf<String, Pair<Int, Int>>()
            // Where the form the page sits in ends, once one is found.
            var pageForm = -1
            var i = 0
            while (i < html.length) {
                val lt = html.indexOf('<', i)
                if (lt < 0) {
                    out.append(html, i, html.length)
                    break
                }
                out.append(html, i, lt)
                if (html.startsWith("<!--", lt)) {
                    val end = html.indexOf("-->", lt + 4)
                    if (end < 0) break
                    out.append(' ')
                    i = end + 3
                    continue
                }
                val name = nameAt(html, lt + 1)
                if (name == null || name !in DROPPED) {
                    out.append('<')
                    i = lt + 1
                    continue
                }
                val gt = html.indexOf('>', lt)
                if (gt < 0) {
                    // No tag ends past here: the rest is text.
                    out.append(html, lt, html.length)
                    break
                }
                i = gt + 1
                out.append(' ')
                if (html[gt - 1] == '/' || name in neverClosed) continue
                // The first closing tag past an earlier opener is the first past this one too, if it's still ahead.
                val close = closers[name]?.takeIf { it.first >= i } ?: closingTag(html, name, i)?.also { closers[name] = it }
                when {
                    close == null -> {
                        neverClosed += name
                        if (name in CODE) break
                    }
                    name == "form" && (close.first <= pageForm || textSize(html.substring(i, close.first)) >= MIN_ARTICLE) ->
                        pageForm = close.first
                    else -> i = close.second
                }
            }
            return out.toString()
        }

        /**
         * The lowercased tag name at [at] ("div" in "<div class=…>", "o:p" in Word's "<o:p>"), or null
         * if there's no tag there: a name starts with a letter ("a < b" and "<3" are text).
         */
        private fun nameAt(html: String, at: Int): String? {
            if (at >= html.length || html[at] !in 'a'..'z' && html[at] !in 'A'..'Z') return null
            var end = at
            // Up to the next "<" at most, so no stretch of the page is read as a name twice.
            while (end < html.length && !html[end].isWhitespace() && html[end] != '>' && html[end] != '/' && html[end] != '<') end++
            return html.substring(at, end).lowercase(Locale.ROOT)
        }

        /** Where the first `<[tag] …>` at or after [from] starts, and where it ends; null if there's none. */
        private fun openingTag(html: String, tag: String, from: Int): Pair<Int, Int>? {
            var at = from
            while (true) {
                val lt = html.indexOf("<$tag", at, ignoreCase = true)
                if (lt < 0) return null
                if (nameAt(html, lt + 1) == tag) {
                    val gt = html.indexOf('>', lt)
                    return if (gt < 0) null else lt to gt + 1
                }
                at = lt + 1
            }
        }

        /** Where the first `</[tag]>` at or after [from] starts, and where it ends; null if there's none. */
        private fun closingTag(html: String, tag: String, from: Int): Pair<Int, Int>? {
            var at = from
            while (true) {
                val lt = html.indexOf("</$tag", at, ignoreCase = true)
                if (lt < 0) return null
                if (nameAt(html, lt + 2) == tag) {
                    val gt = html.indexOf('>', lt)
                    return if (gt < 0) null else lt to gt + 1
                }
                at = lt + 1
            }
        }

        /** What's inside each `<[tag]>…</[tag]>` of [html], outermost first-to-close, in order. */
        private fun elementsOf(html: String, tag: String): List<String> {
            val found = mutableListOf<String>()
            var at = 0
            while (true) {
                val open = openingTag(html, tag, at) ?: break
                val close = closingTag(html, tag, open.second) ?: break
                found += html.substring(open.second, close.first)
                at = close.second
            }
            return found
        }

        /**
         * [html]'s text, tags gone. With [breaks], each paragraph, heading, list item and table row
         * on its own line (cells joined by " | "); the page's own line breaks count as spaces.
         */
        private fun textOf(html: String, breaks: Boolean): String {
            val out = StringBuilder(html.length / 2)
            var i = 0
            while (i < html.length) {
                val lt = html.indexOf('<', i)
                val textEnd = if (lt < 0) html.length else lt
                for (k in i until textEnd) out.append(if (breaks && (html[k] == '\n' || html[k] == '\r')) ' ' else html[k])
                if (lt < 0) break
                val closing = lt + 1 < html.length && html[lt + 1] == '/'
                val name = nameAt(html, if (closing) lt + 2 else lt + 1)
                val markup = name != null || (lt + 1 < html.length && (html[lt + 1] == '!' || html[lt + 1] == '?'))
                val gt = if (markup) html.indexOf('>', lt) else -1
                if (!markup) {
                    // Not a tag: "a < b".
                    out.append('<')
                    i = lt + 1
                    continue
                }
                if (gt < 0) {
                    // No tag ends past here: the rest is text.
                    out.append(html, lt, html.length)
                    break
                }
                out.append(when {
                    // Words run straight through a link or bold text, as the page shows them: "<b>30</b>." is "30.".
                    name != null && (name in INLINE || ':' in name) -> ""
                    !breaks -> " "
                    closing && (name == "td" || name == "th") -> " | "
                    name in BREAKS -> "\n"
                    else -> " "
                })
                i = gt + 1
            }
            return out.toString()
        }

        /**
         * What [page] says about [question] as a few passages in page order, at most [maxChars]
         * in all — the ones sharing the question's rarer words, a number counting a little extra
         * — or blank if nothing on it bears on the question.
         */
        fun passages(page: Page, question: String, maxChars: Int = MAX_EXCERPT): String {
            val terms = termsOf(question).toSet()
            if (terms.isEmpty()) return ""
            val candidates = (listOf(page.description) + page.blocks).filter { it.isNotBlank() }.distinct()
            val termsPer = candidates.map { termsOf(it).toSet() }
            // A page that shares only a word or two of a longer question ("tempo" in a match report,
            // asked about the weather) is about something else.
            val onPage = termsPer.flatten().toSet() + termsOf(page.title)
            if (terms.count { it in onPage } < ceil(terms.size * PAGE_MATCH).toInt().coerceAtLeast(1)) return ""
            // Words on every other line of the page (the team's name on its own site) say little.
            val rarity = terms.associateWith { t -> ln(1.0 + candidates.size.toDouble() / (1 + termsPer.count { t in it })) }
            val scored = candidates.indices.map { i ->
                val hits = terms.filter { it in termsPer[i] }
                val score = hits.sumOf { rarity.getValue(it) } + if (hits.isNotEmpty() && candidates[i].any(Char::isDigit)) 0.3 else 0.0
                Triple(i, score, hits)
            }.filter { it.second > 0 }
            if (scored.isEmpty()) return ""
            val kept = mutableListOf<Pair<Int, String>>()
            var used = 0
            // Best first while they fit; one that doesn't makes way for shorter ones behind it.
            for ((i, _, hits) in scored.sortedByDescending { it.second }) {
                val passage = around(candidates[i], hits)
                if (used + passage.length > maxChars) continue
                kept += i to passage
                used += passage.length + 1
            }
            if (kept.isEmpty()) scored.maxBy { it.second }.let { (i, _, hits) -> kept += i to around(candidates[i], hits).take(maxChars) }
            return kept.sortedBy { it.first }.joinToString("\n") { it.second }
        }

        /** [block] cut to [MAX_PASSAGE] around the first of [hits] it mentions, at word edges. */
        private fun around(block: String, hits: List<String>): String {
            if (block.length <= MAX_PASSAGE) return block
            // Folding changes the text's length (Korean syllables come apart, accents drop off), so
            // each folded character remembers where in the block it came from.
            val folded = StringBuilder()
            val from = mutableListOf<Int>()
            var i = 0
            while (i < block.length) {
                val next = i + Character.charCount(block.codePointAt(i))
                val f = fold(block.substring(i, next))
                folded.append(f)
                repeat(f.length) { from += i }
                i = next
            }
            val hit = hits.mapNotNull { h -> folded.indexOf(h).takeIf { it >= 0 } }.minOrNull()
            val at = hit?.let { from[it] } ?: 0
            val start = (at - MAX_PASSAGE / 3).coerceIn(0, block.length - MAX_PASSAGE)
            val end = start + MAX_PASSAGE
            val cut = block.substring(start, end)
            val head = if (start > 0) "…" + cut.substringAfter(' ') else cut
            return if (end < block.length) head.substringBeforeLast(' ') + "…" else head
        }

        /** [page]'s passages about [question] as an excerpt of [result], or null if it has none. */
        fun excerpt(page: Page, question: String, result: SearchClient.Result): Excerpt? =
            passages(page, question).takeIf { it.isNotBlank() }
                ?.let { Excerpt(result.site, page.title.ifBlank { result.title }, it, result.published) }

        /**
         * The words of [text] that say what it's about, comparable across languages' accents and
         * plurals: lowercased, accents dropped ("notícias" → "noticia"), common words left out,
         * numbers kept.
         */
        fun termsOf(text: String): List<String> =
            fold(text).split(NON_WORD)
                .filter { w -> w.isNotEmpty() && w !in STOPWORDS && (w.length >= 3 || w.all(Char::isDigit)) }
                .map { w -> if (w.length > 4 && w.endsWith('s') && !w.all(Char::isDigit)) w.dropLast(1) else w }

        private fun fold(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")

        private fun metaContent(html: String, name: String): String {
            val wanted = Regex("(?:name|property)\\s*=\\s*[\"']${Regex.escape(name)}[\"']", RegexOption.IGNORE_CASE)
            var at = 0
            while (true) {
                val (start, end) = openingTag(html, "meta", at) ?: return ""
                val tag = html.substring(start, end)
                if (wanted.containsMatchIn(tag)) {
                    return decodeEntities(Regex("content\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)
                        ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }.orEmpty())
                }
                at = end
            }
        }

        private fun stripTags(html: String) = textOf(html, breaks = false)

        /** How much text [html] holds, not counting the spaces its markup is indented with. */
        private fun textSize(html: String) = stripTags(html).count { !it.isWhitespace() }

        /** HTML entities as the characters they stand for — named ones common in English and Portuguese pages, and numbered ones. */
        fun decodeEntities(text: String): String {
            if ('&' !in text) return text
            return ENTITY.replace(text) { m ->
                val name = m.groupValues[1]
                when {
                    name.startsWith("#x") || name.startsWith("#X") -> codePoint(name.drop(2).toIntOrNull(16), m.value)
                    name.startsWith("#") -> codePoint(name.drop(1).toIntOrNull(), m.value)
                    else -> NAMED[name] ?: m.value
                }
            }
        }

        private fun codePoint(cp: Int?, original: String): String =
            if (cp == null || !Character.isValidCodePoint(cp)) original else String(Character.toChars(cp))

        // What's never the answer: code, menus, headers and footers, forms, embeds…
        private val DROPPED = setOf("script", "style", "noscript", "template", "svg", "iframe", "nav", "header", "footer",
            "aside", "form", "button", "select", "figure", "textarea")
        // …of which code and the like, whose "tags" are text: never closed, it runs to the end.
        private val CODE = setOf("script", "style", "template", "textarea", "noscript")
        // Tags within a line of text.
        private val INLINE = setOf("a", "span", "b", "strong", "i", "em", "u", "s", "small", "big", "sub", "sup", "mark", "abbr",
            "cite", "code", "q", "time", "font", "bdi", "bdo", "data", "dfn", "kbd", "samp", "var", "wbr", "del", "ins", "nobr")
        // Tags that start a new line of text.
        private val BREAKS = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table", "section", "article",
            "main", "blockquote", "dd", "dt", "dl", "ul", "ol", "pre", "figcaption", "br", "hr")
        private val SPACES = Regex("\\s+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val MARKS = Regex("\\p{M}+")
        private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,6}|#\\d{1,7}|[a-zA-Z][a-zA-Z0-9]{1,8});")
        // A line shorter than this is a label or a link, not a sentence.
        private const val MIN_BLOCK = 30
        // An <article> with fewer letters than this is a teaser card, not the page's article.
        private const val MIN_ARTICLE = 400

        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–",
            "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«",
            "raquo" to "»", "middot" to "·", "bull" to "•", "deg" to "°", "ordm" to "º", "ordf" to "ª", "euro" to "€",
            "pound" to "£", "cent" to "¢", "copy" to "©", "reg" to "®", "trade" to "™", "times" to "×", "divide" to "÷",
            "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "sup2" to "²", "sup3" to "³", "iexcl" to "¡", "iquest" to "¿",
            "aacute" to "á", "Aacute" to "Á", "agrave" to "à", "Agrave" to "À", "acirc" to "â", "Acirc" to "Â",
            "atilde" to "ã", "Atilde" to "Ã", "auml" to "ä", "eacute" to "é", "Eacute" to "É", "egrave" to "è",
            "ecirc" to "ê", "Ecirc" to "Ê", "iacute" to "í", "Iacute" to "Í", "icirc" to "î", "oacute" to "ó",
            "Oacute" to "Ó", "ocirc" to "ô", "Ocirc" to "Ô", "otilde" to "õ", "Otilde" to "Õ", "ouml" to "ö",
            "uacute" to "ú", "Uacute" to "Ú", "uuml" to "ü", "Uuml" to "Ü", "ccedil" to "ç", "Ccedil" to "Ç",
            "ntilde" to "ñ", "Ntilde" to "Ñ",
        )

        // Words too common to say what a question is about, in English and Portuguese (accents already dropped).
        private val STOPWORDS = setOf(
            "the", "and", "for", "are", "was", "were", "who", "what", "whats", "when", "where", "which", "how", "does", "did",
            "has", "have", "had", "with", "from", "that", "this", "these", "those", "you", "your", "can", "could", "will",
            "would", "about", "into", "than", "then", "there", "their", "they", "them", "its", "not", "but", "all", "any",
            "tell", "me", "please", "now", "today", "yesterday", "tomorrow", "right", "latest", "current",
            "que", "qual", "quais", "quem", "quando", "onde", "como", "quanto", "quanta", "quantos", "quantas", "para",
            "pra", "pro", "por", "com", "sem", "uma", "uns", "umas", "dos", "das", "nos", "nas", "num", "numa", "esse",
            "essa", "isso", "este", "esta", "isto", "aquele", "aquela", "ele", "ela", "eles", "elas", "voce", "voces",
            "meu", "minha", "seu", "sua", "nosso", "nossa", "mais", "menos", "muito", "sobre", "entre", "hoje", "ontem",
            "amanha", "agora", "tem", "sao", "foi", "era", "vai", "vou", "ser", "estar", "esta", "estao", "tambem", "ainda",
            "diz", "fala", "sabe", "sabes", "gostaria", "saber", "naomi",
        )
    }
}
