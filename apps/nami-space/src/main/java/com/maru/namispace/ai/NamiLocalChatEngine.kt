package com.maru.namispace.ai

import android.content.Context
import com.maru.namispace.engine.GameManager
import com.maru.namispace.model.AffectionTier
import com.maru.namispace.model.AutonomousActivity
import com.maru.namispace.model.NamiMood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-Level Orchestrator for 100% Pure Local On-Device AI Chat.
 * Connects prompt building, local neural inference, grounded search, and mood expression mapping.
 */
class NamiLocalChatEngine(private val context: Context) {

    val onDeviceLlm = NamiOnDeviceLlm(context)

    val isReady: Boolean
        get() = onDeviceLlm.isModelAvailable

    data class LocalResponse(
        val text: String,
        val mood: NamiMood,
        val wasGrounded: Boolean = false,
        val suggestedChips: List<String> = emptyList(),
    )

    /**
     * Generates a fully local in-character response from Nanami.
     */
    suspend fun generateChatReply(
        userMessage: String,
        gameManager: GameManager,
        conversationHistory: List<Pair<String, Boolean>> = emptyList(),
    ): LocalResponse = withContext(Dispatchers.IO) {
        val session = gameManager.state.value
        val char = session.character
        val tier = gameManager.getAffectionTier(char.affection)

        // 1. Check if grounded search is needed (with 0ms offline bailout)
        var searchContext = ""
        var wasGrounded = false
        if (NamiGroundedSearch.shouldSearch(context, userMessage)) {
            val searchResults = NamiGroundedSearch.search(userMessage)
            if (searchResults.isNotEmpty()) {
                searchContext = NamiGroundedSearch.formatContext(searchResults)
                wasGrounded = true
            }
        }

        // 2. Build system instruction with live stats and search context
        val basePrompt = SystemPrompt.buildCompactPrompt(
            hunger = char.hunger,
            energy = char.energy,
            affectionTier = tier.label,
            activity = session.currentActivity.label,
        )

        val fullSystemPrompt = if (searchContext.isNotBlank()) {
            "$basePrompt\n\n$searchContext\nInstruction: Use the grounding context to provide an accurate, fact-based answer in your natural tsundere Nami tone."
        } else {
            basePrompt
        }

        // 3. Format structured chat messages
        val messages = mutableListOf<NamiOnDeviceLlm.ChatMessage>()
        messages.add(NamiOnDeviceLlm.ChatMessage(role = "system", content = fullSystemPrompt))

        conversationHistory.takeLast(4).forEach { (msg, isUser) ->
            if (isUser) {
                messages.add(NamiOnDeviceLlm.ChatMessage(role = "user", content = msg))
            } else {
                messages.add(NamiOnDeviceLlm.ChatMessage(role = "assistant", content = msg))
            }
        }
        messages.add(NamiOnDeviceLlm.ChatMessage(role = "user", content = userMessage))

        // 4. Run local on-device neural inference
        val localResult = onDeviceLlm.generateChat(messages, temperature = 0.7f, maxTokens = 120)

        val rawReplyText = localResult.getOrElse { err ->
            "⚡ [Neural Model Error]: ${err.localizedMessage ?: err.message}. Awaiting compatible model..."
        }

        // 5. Parse mood tags and clean dialogue text
        val parsed = TagParser.parse(rawReplyText)
        val finalMood = parsed.mood ?: mapMoodFromText(parsed.text)
        val cleanedText = parsed.text.trim()

        // 6. Generate dynamic suggestion chips
        val chips = parsed.suggestedReplies.ifEmpty {
            listOf("🎵 Favorite song?", "🥑 Want a snack?", "💤 Let's rest", "what is a qubit?")
        }

        LocalResponse(
            text = cleanedText,
            mood = finalMood,
            wasGrounded = wasGrounded,
            suggestedChips = chips
        )
    }

    /**
     * Generates a 1-sentence dynamic ambient thought for the Home Screen speech bubble.
     */
    suspend fun generateAmbientThought(
        activity: AutonomousActivity,
        affectionTier: AffectionTier,
        mood: NamiMood,
        actionTrigger: String,
    ): String = withContext(Dispatchers.IO) {
        val timeOfDay = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Sunset Twilight"
            else -> "Late Night"
        }

        val prompt = SystemPrompt.buildAmbientPrompt(
            activity = activity.label,
            timeOfDay = timeOfDay,
            mood = mood.label,
            affectionTier = affectionTier.label,
            actionTrigger = actionTrigger,
        )

        val ambientMessages = listOf(
            NamiOnDeviceLlm.ChatMessage(role = "system", content = prompt),
            NamiOnDeviceLlm.ChatMessage(role = "user", content = "Generate your single ambient thought line:")
        )
        val res = onDeviceLlm.generateChat(ambientMessages, temperature = 0.8f, maxTokens = 64)
        res.getOrElse {
            activity.label
        }
    }

    private fun mapMoodFromText(text: String): NamiMood {
        val lower = text.lowercase()
        return when {
            lower.contains("!!!") || lower.contains("wah") -> NamiMood.PANIC
            lower.contains("...") && text.length < 20 -> NamiMood.SAD
            lower.contains("hmph") || lower.contains("baka") || lower.contains("mou") -> NamiMood.TSUNDERE
            lower.contains("happy") || lower.contains("love") || lower.contains("ehehe") || lower.contains("✨") -> NamiMood.HAPPY
            lower.contains("angry") || lower.contains("annoying") -> NamiMood.ANGRY
            lower.contains("shy") || lower.contains("blush") || lower.contains("fluster") -> NamiMood.BLUSH
            lower.contains("sleep") || lower.contains("yawn") -> NamiMood.SLEEPY
            lower.contains("surprise") || lower.contains("eh?!") || lower.contains("nani") || lower.contains("😱") -> NamiMood.SURPRISE
            else -> NamiMood.TSUNDERE
        }
    }
}
