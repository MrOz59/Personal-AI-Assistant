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
 * SearXNG when one is set, else — or when it can't be reached — DuckDuckGo from the phone: its
 * results page, or, when that answers with a CAPTCHA (it does, to anything bot-like), its instant
 * answers, which cover what Wikipedia knows. Only titles and snippets are read, never the pages,
 * so an answer takes a second or two. Network-bound: call off the main thread.
 */
class SearchClient {

    /** One search result: its [title], a [snippet] of the page, the [site] it's on and, if known, when it was [published]. */
    data class Result(val title: String, val snippet: String, val site: String = "", val published: String? = null)

    /** What a search found, and where from (for the log). */
    data class Found(val results: List<Result>, val source: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * The top results for [query], for someone speaking [english] ("en-AU"), from the SearXNG at
     * [searxng] (blank: none) or DuckDuckGo. Null if nothing could be found anywhere.
     */
    suspend fun search(query: String, searxng: String, english: String): Found? = withContext(Dispatchers.IO) {
        if (searxng.isNotBlank()) {
            attempt("SearXNG") { searxng(searxng, query, english) }?.let { return@withContext Found(it, "SearXNG") }
        }
        attempt("DuckDuckGo") { duckDuckGo(query, english) }?.let { return@withContext Found(it, "DuckDuckGo") }
        attempt("DuckDuckGo instant answers") { instantAnswers(query) }?.let { Found(it, "DuckDuckGo instant answers") }
    }

    private fun attempt(source: String, search: () -> List<Result>?): List<Result>? =
        runCatching(search)
            .onFailure { android.util.Log.w("Naomi", "$source search failed: ${it.message}") }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    private fun searxng(base: String, query: String, english: String): List<Result>? {
        val url = "${base.trimEnd('/')}/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("language", english)
            ?.build() ?: return null
        return get(url.toString())?.let { fromSearxng(JSONObject(it)) }
    }

    private fun duckDuckGo(query: String, english: String): List<Result>? {
        val url = "https://html.duckduckgo.com/html/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("kl", duckRegion(english))
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
    suspend fun trySearxng(base: String, query: String, english: String): List<Result> = withContext(Dispatchers.IO) {
        searxng(base, query, english) ?: throw IOException("that isn't a server address")
    }

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            // SearXNG says 403 when its JSON output is off.
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}" + if (r.code == 403) " (is JSON output on?)" else "")
            r.body?.string()
        }

    companion object {
        // DuckDuckGo's results page is made for browsers without JavaScript; it turns away clients that don't look like one.
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Naomi-Assistant/1.0"
        // Results she reads: enough to cross-check, few enough to answer in a second or two.
        const val MAX_RESULTS = 5
        private const val MAX_SNIPPET = 300

        /** A SearXNG JSON answer's results: its direct answers and infobox first, then the pages. */
        fun fromSearxng(json: JSONObject): List<Result> {
            val out = mutableListOf<Result>()
            json.optJSONArray("answers")?.let { answers ->
                for (i in 0 until answers.length()) {
                    // Plain strings in older versions, {"answer": …} objects in newer ones.
                    val text = answers.optJSONObject(i)?.optString("answer") ?: answers.optString(i)
                    if (!text.isNullOrBlank()) out += Result("Direct answer", clean(text))
                }
            }
            json.optJSONArray("infoboxes")?.optJSONObject(0)?.let { box ->
                val text = clean(box.optString("content"))
                if (text.isNotBlank()) out += Result(clean(box.optString("infobox")), text, "Wikipedia")
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
                    val title = Regex("class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                    val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                    val site = Regex("class=\"result__url\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                    if (title == null || snippet.isNullOrBlank()) null
                    else Result(clean(title), clean(snippet), siteOf(clean(site.orEmpty())))
                }
                .take(MAX_RESULTS)
        }

        /** DuckDuckGo's instant answer: a direct answer, a definition, or the abstract (usually Wikipedia's). */
        fun fromInstantAnswers(json: JSONObject): List<Result> = listOfNotNull(
            json.optString("Answer").takeIf { it.isNotBlank() }?.let { Result("Direct answer", clean(it)) },
            json.optString("AbstractText").takeIf { it.isNotBlank() }?.let {
                Result(clean(json.optString("Heading")), clean(it), json.optString("AbstractSource"))
            },
            json.optString("Definition").takeIf { it.isNotBlank() }?.let {
                Result("Definition", clean(it), json.optString("DefinitionSource"))
            },
        )

        /** DuckDuckGo's region code for an English locale: "en-AU" → "au-en"; "wt-wt" (no region) if it has none. */
        fun duckRegion(english: String): String =
            english.substringAfter('-', "").lowercase().takeIf { it.length == 2 }?.let { "$it-en" } ?: "wt-wt"

        /** The site a link is on, without "www.": "https://www.abc.net.au/news/…" → "abc.net.au". */
        fun siteOf(url: String): String =
            url.trim().substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")

        /** Text from a result as plain words: no tags, entities decoded, whitespace squeezed, cut at [MAX_SNIPPET]. */
        fun clean(text: String): String {
            val plain = text.replace(Regex("<[^>]+>"), "")
                .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
                .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toInt().toChar().toString() }
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
                .replace("&ndash;", "–").replace("&mdash;", "—").replace("&hellip;", "…")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace(Regex("\\s+"), " ").trim()
            if (plain.length <= MAX_SNIPPET) return plain
            return plain.take(MAX_SNIPPET).substringBeforeLast(' ') + "…"
        }
    }
}
