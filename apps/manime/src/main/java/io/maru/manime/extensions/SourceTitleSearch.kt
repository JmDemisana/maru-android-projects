package io.maru.manime.extensions

import java.text.Normalizer
import java.util.Locale

private val whitespace = Regex("\\s+")
private val symbols = Regex("[^\\p{L}\\p{M}\\p{N}\\s]+")
private val animationSuffix = Regex("\\s+(?:the\\s+)?animation$", RegexOption.IGNORE_CASE)
private val subtitleSeparator = Regex("[:：~～]|\\s+[-–—]\\s+")
private val editionWords = setOf(
    "season", "part", "volume", "vol", "tv", "ova", "oad", "ona", "special", "movie", "remake",
    "censored", "uncensored", "edition", "cut"
)

private fun tidyTitle(title: String): String = title.replace(whitespace, " ").trim()

private fun titleWords(title: String): String = tidyTitle(
    Normalizer.normalize(title, Normalizer.Form.NFKC)
        .replace('×', 'x').replace('✕', 'x')
        .replace("'", "").replace("’", "")
        .replace(symbols, " ")
)

internal fun sourceTitleQueries(aliases: List<String>): List<String> {
    val originals = aliases.map(::tidyTitle).filter { it.length >= 2 }
        .distinctBy { it.lowercase(Locale.ROOT) }.take(6)
    fun variants(title: String): List<String> {
        val words = titleWords(title)
        val shortened = listOf(
            words.replace(animationSuffix, ""),
            tidyTitle(title.split(subtitleSeparator, limit = 2).first())
        ).filter { it.length >= 4 }
        return shortened + listOf(
            tidyTitle(title.replace(symbols, " ")),
            tidyTitle(title.replace(symbols, "")),
            words,
            words.replace(animationSuffix, "").split(' ').sorted().joinToString(" ")
        )
    }
    return (originals + originals.flatMap(::variants))
        .filter { it.length >= 2 }
        .distinctBy { it.lowercase(Locale.ROOT) }
}

internal fun matchesSourceSeriesTitle(source: String, aliases: List<String>): Boolean {
    fun normalized(title: String) = titleWords(title).replace(animationSuffix, "").lowercase(Locale.ROOT)
    val actual = normalized(source)
    if (actual.isBlank()) return false
    val words = actual.split(' ')
    return aliases.any { alias ->
        val expected = normalized(alias)
        if (actual == expected) return@any true
        val expectedWords = expected.split(' ')
        if (actual.replace(" ", "") == expected.replace(" ", "") &&
            words.filter { it.any(Char::isDigit) } == expectedWords.filter { it.any(Char::isDigit) }
        ) return@any true
        // Reordering season and part labels can make different editions look identical.
        words.size >= 2 && words.size == expectedWords.size &&
            words.none { word -> word.any(Char::isDigit) || word in editionWords } &&
            words.sorted() == expectedWords.sorted()
    }
}
