package com.naomi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Web search for what she answers by looking it up ("who won last night?"): the user's own
 * SearXNG when there is one (see [BrainSettings.searchServer]), else — or when it can't be
 * reached — DuckDuckGo from the phone: its results page, or, when that answers with a CAPTCHA (it
 * does, to anything bot-like), its instant answers, which cover what Wikipedia knows. This reads
 * titles and snippets; [PageReader] opens the pages when those aren't enough. Network-bound: call
 * off the main thread.
 */
class SearchClient {

    /**
     * One search result: its [title], a [snippet] of the page, the [site] it's on, when it was
     * [published] if known, and the page's [url] (blank for a direct answer with none).
     */
    data class Result(
        val title: String,
        val snippet: String,
        val site: String = "",
        val published: String? = null,
        val url: String = "",
    )

    /** What a search found, and where from (for the log). */
    data class Found(val results: List<Result>, val source: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * The top results for [query], for someone speaking [language] ("en-AU", "pt-BR"), from the SearXNG at
     * [searxng] (blank: none) or DuckDuckGo. Null if nothing could be found anywhere.
     */
    suspend fun search(query: String, searxng: String, language: String): Found? = withContext(Dispatchers.IO) {
        val server = searxng.trimEnd('/')
        if (server.isNotBlank() && System.currentTimeMillis() >= (searxngDown[server] ?: 0L)) {
            val failed = runCatching { searxng(server, query, language) }
                .onSuccess { searxngDown.remove(server) }
                .onFailure {
                    android.util.Log.w("Naomi", "SearXNG search failed: ${it.message}")
                    searxngDown[server] = System.currentTimeMillis() + SEARXNG_RETRY_MS
                }
            failed.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext Found(it, "SearXNG") }
        }
        attempt("DuckDuckGo") { duckDuckGo(query, language) }?.let { return@withContext Found(it, "DuckDuckGo") }
        attempt("DuckDuckGo instant answers") { instantAnswers(query) }?.let { Found(it, "DuckDuckGo instant answers") }
    }

    private fun attempt(source: String, search: () -> List<Result>?): List<Result>? =
        runCatching(search)
            .onFailure { android.util.Log.w("Naomi", "$source search failed: ${it.message}") }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    private fun searxng(base: String, query: String, language: String): List<Result>? {
        val url = "${base.trimEnd('/')}/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("language", language)
            // Don't wait on its slowest search engine past this (seconds).
            ?.addQueryParameter("timeout_limit", "3.0")
            ?.build() ?: return null
        val json = get(url.toString())?.let(::JSONObject) ?: return null
        json.optJSONArray("unresponsive_engines")?.takeIf { it.length() > 0 }?.let {
            android.util.Log.w("Naomi", "SearXNG engines that didn't answer: $it")
        }
        return fromSearxng(json)
    }

    private fun duckDuckGo(query: String, language: String): List<Result>? {
        val url = "https://html.duckduckgo.com/html/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("kl", duckRegion(language))
            .build()
        return get(url.toString())?.let(::fromDuckDuckGo)
    }

    private fun instantAnswers(query: String): List<Result>? {
        val url = "https://api.duckduckgo.com/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()
        return get(url.toString())?.let { fromInstantAnswers(JSONObject(it)) }
    }

    /**
     * The SearXNG at [base]'s results for [query], for the settings screen's test. Throws, with
     * what went wrong, if it can't be reached or won't answer in JSON.
     */
    suspend fun trySearxng(base: String, query: String, language: String): List<Result> = withContext(Dispatchers.IO) {
        val results = try {
            searxng(base, query, language) ?: throw IOException("that isn't a server address")
        } catch (e: java.net.UnknownServiceException) {
            // Android allows plain http only to the owner's machines by their Tailscale name (see network_security_config).
            throw IOException("plain http only works with the full Tailscale name, like http://<pc>.<tailnet>.ts.net:8888")
        }
        // It works now: don't keep skipping it for an earlier failure.
        searxngDown.remove(base.trimEnd('/'))
        results
    }

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            // SearXNG says 403 when its JSON output is off, and 429 when its bot limiter is on.
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}" + when (r.code) {
                403 -> " (is JSON output on?)"
                429 -> " (turn its limiter off)"
                else -> ""
            })
            r.body?.string()
        }

    companion object {
        // DuckDuckGo's results page is made for browsers without JavaScript; it turns away clients that don't look like one.
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Naomi-Assistant/1.0"
        // Results she reads: enough to cross-check, few enough to answer in a second or two.
        const val MAX_RESULTS = 5
        private const val MAX_SNIPPET = 300
        // How long a SearXNG that failed is left alone before she tries it again.
        const val SEARXNG_RETRY_MS = 2 * 60_000L

        // A SearXNG that just failed (the PC is off, say) is skipped until then, so each question
        // doesn't wait out its timeout again before trying DuckDuckGo. Shared, so a working test
        // from the settings screen ends it; by address, so a newly saved one is tried straight away.
        private val searxngDown = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** A SearXNG JSON answer's results: its direct answers and infobox first, then the pages. */
        fun fromSearxng(json: JSONObject): List<Result> {
            val out = mutableListOf<Result>()
            json.optJSONArray("answers")?.let { answers ->
                for (i in 0 until answers.length()) {
                    // Plain strings in older versions, {"answer": …} objects in newer ones.
                    val answer = answers.optJSONObject(i)
                    val text = answer?.optString("answer") ?: answers.optString(i)
                    val url = answer?.optString("url").orEmpty().takeIf { it.startsWith("http") }.orEmpty()
                    if (!text.isNullOrBlank()) out += Result("Direct answer", clean(text), siteOf(url), url = url)
                }
            }
            json.optJSONArray("infoboxes")?.optJSONObject(0)?.let { box ->
                val text = clean(box.optString("content"))
                // Its id is the article's address ("https://pt.wikipedia.org/wiki/…") for Wikipedia's and Wikidata's.
                val url = box.optString("id").takeIf { it.startsWith("http") }.orEmpty()
                val site = siteOf(url).ifBlank { box.optString("engine").replaceFirstChar { it.uppercase() }.ifBlank { "Wikipedia" } }
                if (text.isNotBlank()) out += Result(clean(box.optString("infobox")), text, site, url = url)
            }
            val results = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                if (out.size >= MAX_RESULTS + 2) break
                val r = results.optJSONObject(i) ?: continue
                val snippet = clean(r.optString("content"))
                if (snippet.isBlank()) continue
                out += Result(
                    clean(r.optString("title")), snippet, siteOf(r.optString("url")),
                    r.optString("publishedDate").takeIf { it.length >= 10 && it != "null" }?.take(10),
                    r.optString("url"),
                )
            }
            return out.take(MAX_RESULTS + 2)
        }

        /** Results from DuckDuckGo's HTML page, ads skipped; empty if it answered with a CAPTCHA instead. */
        fun fromDuckDuckGo(html: String): List<Result> {
            if (html.contains("anomaly-modal") || html.contains("anomaly.js")) {
                android.util.Log.w("Naomi", "DuckDuckGo asked for a CAPTCHA")
                return emptyList()
            }
            return html.split(Regex("<div class=\"result[ \"]")).drop(1)
                .filterNot { it.substringBefore('>').contains("result--ad") }
                .mapNotNull { block ->
                    val link = Regex("<a[^>]*class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(block)
                    val title = link?.groupValues?.get(1)
                    val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                    val site = Regex("class=\"result__url\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                    val href = link?.value?.let { Regex("href=\"([^\"]*)\"").find(it)?.groupValues?.get(1) }.orEmpty()
                    if (title == null || snippet.isNullOrBlank()) null
                    else Result(clean(title), clean(snippet), siteOf(clean(site.orEmpty())), url = duckTarget(href))
                }
                .take(MAX_RESULTS)
        }

        /** DuckDuckGo's instant answer: a direct answer, a definition, or the abstract (usually Wikipedia's). */
        fun fromInstantAnswers(json: JSONObject): List<Result> = listOfNotNull(
            json.optString("Answer").takeIf { it.isNotBlank() }?.let { Result("Direct answer", clean(it)) },
            json.optString("AbstractText").takeIf { it.isNotBlank() }?.let {
                Result(clean(json.optString("Heading")), clean(it), json.optString("AbstractSource"), url = json.optString("AbstractURL"))
            },
            json.optString("Definition").takeIf { it.isNotBlank() }?.let {
                Result("Definition", clean(it), json.optString("DefinitionSource"))
            },
        )

        /** DuckDuckGo's region code for a language tag: "en-AU" → "au-en", "pt-BR" → "br-pt"; "wt-wt" (no region) if it has none. */
        fun duckRegion(language: String): String {
            val region = language.substringAfter('-', "").lowercase().takeIf { it.length == 2 } ?: return "wt-wt"
            return "$region-${language.substringBefore('-').lowercase()}"
        }

        /**
         * Where a DuckDuckGo result link goes: its "//duckduckgo.com/l/?uddg=<address>" redirect
         * read for the address inside, or the link itself if it's direct. Blank if it's neither.
         */
        fun duckTarget(href: String): String {
            val link = href.replace("&amp;", "&").let { if (it.startsWith("//")) "https:$it" else it }
            val parsed = link.toHttpUrlOrNull() ?: return ""
            if (parsed.host.endsWith("duckduckgo.com")) return parsed.queryParameter("uddg").orEmpty()
            return link
        }

        /** The site a link is on, without "www.": "https://www.abc.net.au/news/…" → "abc.net.au". */
        fun siteOf(url: String): String =
            url.trim().substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")

        /** Text from a result as plain words: no tags, entities decoded, whitespace squeezed, cut at [MAX_SNIPPET]. */
        fun clean(text: String): String {
            val plain = text.replace(Regex("<[^>]+>"), "")
                .replace(Regex("&#x([0-9a-fA-F]{1,6});")) { m -> codePoint(m.groupValues[1].toInt(16), m.value) }
                .replace(Regex("&#(\\d{1,7});")) { m -> codePoint(m.groupValues[1].toInt(), m.value) }
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
                .replace("&ndash;", "–").replace("&mdash;", "—").replace("&hellip;", "…")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace(Regex("\\s+"), " ").trim()
            if (plain.length <= MAX_SNIPPET) return plain
            return plain.take(MAX_SNIPPET).substringBeforeLast(' ') + "…"
        }

        // A numbered entity's character — emoji and all, which don't fit in one Char.
        private fun codePoint(cp: Int, original: String): String =
            if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else original
    }
}
