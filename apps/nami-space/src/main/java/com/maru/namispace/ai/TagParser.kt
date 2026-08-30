package com.maru.namispace.ai

import com.maru.namispace.model.NamiMood

/**
 * Parses special bracket tags from Nami's responses.
 * Extracts mood tags, search permissions, memories, and suggested replies.
 */
object TagParser {

    data class ParsedResponse(
        val text: String,
        val mood: NamiMood? = null,
        val searchQuery: String? = null,
        val memories: List<String> = emptyList(),
        val suggestedReplies: List<String> = emptyList(),
        val bubbles: List<String> = emptyList(),
    )

    private val MOOD_PATTERN = Regex("""\[?MOOD:\s*([A-Za-z0-9_]+)\]?""", RegexOption.IGNORE_CASE)
    private val SEARCH_PATTERN = Regex("""\[?SEARCH_PERMISSION:\s*([^\]]+)\]?""", RegexOption.IGNORE_CASE)
    private val REMEMBER_PATTERN = Regex("""\[?REMEMBER:\s*([^\]]+)\]?""", RegexOption.IGNORE_CASE)
    private val SUGGESTED_PATTERN = Regex("""\[?SUGGESTED:\s*([^\]]+)\]?""", RegexOption.IGNORE_CASE)
    private val BUBBLE_PATTERN = Regex("""\[?NEXT:(\d+(?:\.\d+)?)\]?""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ParsedResponse {
        var text = raw

        // Extract mood
        val moodMatch = MOOD_PATTERN.find(text)
        val moodName = moodMatch?.groupValues?.get(1)?.trim()?.uppercase()
        val parsedMood = moodName?.let { name ->
            try {
                NamiMood.valueOf(name)
            } catch (e: Exception) {
                when {
                    name.contains("SURPRISE") || name.contains("SHOCK") -> NamiMood.SURPRISE
                    name.contains("TSUNDERE") || name.contains("HMPH") -> NamiMood.TSUNDERE
                    name.contains("HAPPY") || name.contains("JOY") -> NamiMood.HAPPY
                    name.contains("BLUSH") || name.contains("FLUSTER") -> NamiMood.BLUSH
                    name.contains("POUT") -> NamiMood.POUT
                    name.contains("LOVE") -> NamiMood.LOVE
                    name.contains("SHY") -> NamiMood.SHY
                    name.contains("THINK") -> NamiMood.THINKING
                    name.contains("ANGRY") -> NamiMood.ANGRY
                    name.contains("SAD") -> NamiMood.SAD
                    name.contains("PANIC") -> NamiMood.PANIC
                    name.contains("SLEEP") -> NamiMood.SLEEPY
                    else -> null
                }
            }
        }
        text = MOOD_PATTERN.replace(text, "").trim()

        // Extract suggested replies (Must be at least 3 characters each)
        val suggestedMatch = SUGGESTED_PATTERN.find(text)
        val suggestedReplies = suggestedMatch?.groupValues?.get(1)
            ?.split("|")
            ?.map { it.trim().trim('"', '\'', '[', ']') }
            ?.filter { it.length >= 3 }
            ?: emptyList()
        text = SUGGESTED_PATTERN.replace(text, "").trim()

        // Extract search permission
        val searchMatch = SEARCH_PATTERN.find(text)
        val searchQuery = searchMatch?.groupValues?.get(1)?.trim()
        text = SEARCH_PATTERN.replace(text, "").trim()

        // Extract memories
        val memories = REMEMBER_PATTERN.findAll(text).map {
            it.groupValues[1].trim()
        }.toList()
        text = REMEMBER_PATTERN.replace(text, "").trim()

        // Split into bubbles
        val bubbleParts = BUBBLE_PATTERN.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val bubbles = if (bubbleParts.size > 1) bubbleParts else emptyList()
        text = BUBBLE_PATTERN.replace(text, "").trim()

        // Clean out any native log remnants if present
        text = text.lines()
            .filterNot { line ->
                line.contains("common_perf_print") ||
                line.contains("system_info:") ||
                line.contains("sampler params:") ||
                line.contains("llama_completion:") ||
                line.matches(Regex("""^\d+\.\d+\.\d+\.\d+\s+[IWE].*"""))
            }
            .joinToString("\n")
            .trim()

        return ParsedResponse(
            text = text.trim(),
            mood = parsedMood,
            searchQuery = searchQuery,
            memories = memories,
            suggestedReplies = suggestedReplies,
            bubbles = bubbles,
        )
    }
}
