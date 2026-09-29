package com.naomi.assistant

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Answering a question by looking it up: search, answer from the results' snippets, and — when
 * they don't say — from passages of the top pages ([PageReader]). The pages are fetched while the
 * brain reads the snippets, so reading them adds little wait; and it never takes more than two
 * calls to the brain, since a small local model is slow.
 *
 * The three steps are passed in — the search, the page reader and the brain — so the flow can be
 * tried without a network.
 */
class LookUp(
    private val search: suspend (query: String) -> SearchClient.Found?,
    private val read: suspend (results: List<SearchClient.Result>, question: String) -> List<PageReader.Excerpt>,
    private val answer: suspend (
        results: List<SearchClient.Result>,
        pages: List<PageReader.Excerpt>,
        creative: Boolean,
    ) -> CloudBrain.Attempt,
) {

    /** How a look-up ended. */
    sealed interface Outcome {
        /** Nothing came back from any search. */
        object NoResults : Outcome

        /** Answered, in her words — from the pages themselves if [fromPages]. */
        data class Answered(val say: String, val fromPages: Boolean) : Outcome

        /** Results, but no answer in them: her own line saying so ([say]), if she had one. */
        data class NotAnswered(val say: String?) : Outcome
    }

    /** [query] searched, and [asked] — what they actually said — answered from what turns up. */
    suspend fun run(query: String, asked: String): Outcome = coroutineScope {
        val found = search(query) ?: return@coroutineScope Outcome.NoResults
        // Opened now, in case the snippets don't answer: by the time the brain says so, they're (nearly) read.
        val pages = async { read(found.results, "$asked $query") }
        val first = answer(found.results, emptyList(), true)
        when (first.verdict) {
            CloudBrain.Verdict.ANSWERED -> {
                pages.cancel()
                return@coroutineScope Outcome.Answered(first.answer!!.say, fromPages = false)
            }
            CloudBrain.Verdict.UNREACHABLE -> {
                pages.cancel()
                return@coroutineScope Outcome.NotAnswered(null)
            }
            else -> {}
        }
        val excerpts = pages.await()
        android.util.Log.i("Naomi", "Snippets didn't answer (${first.verdict}); read ${excerpts.size} pages")
        val second = when {
            // A steadier try with the pages, and only the top snippets, so a small model's context holds it all.
            excerpts.isNotEmpty() -> answer(found.results.take(SNIPPETS_WITH_PAGES), excerpts, false)
            // Nothing read, but the first answer strayed from the results: once more, steadier.
            first.verdict == CloudBrain.Verdict.UNSURE -> answer(found.results, emptyList(), false)
            else -> null
        }
        if (second?.verdict == CloudBrain.Verdict.ANSWERED) Outcome.Answered(second.answer!!.say, fromPages = excerpts.isNotEmpty())
        else Outcome.NotAnswered((second?.answer ?: first.answer)?.say)
    }

    companion object {
        // Snippets kept alongside the pages: the top ones, for dates and a cross-check.
        const val SNIPPETS_WITH_PAGES = 3
    }
}
