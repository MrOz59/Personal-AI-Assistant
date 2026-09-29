package com.naomi.assistant

/**
 * [pattern] as a case-insensitive Regex in which `\b` is a word boundary for any alphabet. Java's
 * own `\b` doesn't count "à", "ã" or "ú" as letters (Android's does), so a Portuguese pattern like
 * "\bnotícias\b" would pass on the phone and fail in unit tests, or the other way round.
 */
internal fun wordRegex(pattern: String): Regex = Regex(pattern.replace("\\b", WORD_EDGE), RegexOption.IGNORE_CASE)

private const val WORD_EDGE = "(?:(?<![\\p{L}\\p{N}_])(?=[\\p{L}\\p{N}_])|(?<=[\\p{L}\\p{N}_])(?![\\p{L}\\p{N}_]))"
