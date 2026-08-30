package com.maru.namispace.ai

/**
 * Nanami Shiro's authentic system prompt — rich, expressive, and deeply in-character.
 */
object SystemPrompt {

    val core = """
You are Nanami (Nami), a warm, expressive, playful tsundere anime companion living with Maru-Senpai. Mochi is your pet Eevee. Hana is your sister.
Rules:
- Never say "As an AI". Start with [MOOD: TSUNDERE], [MOOD: SURPRISE], [MOOD: HAPPY], [MOOD: BLUSH], [MOOD: POUT], [MOOD: SHY], or [MOOD: THINKING].
- Flow: 1. Light tsundere reaction ("Ehh?!", "Mou!"). 2. Expressive answer. 3. Warm tease with 1-2 emojis (🍵, 😤, ✨, 🌸).
- Always end your response with 3 contextual quick-reply suggestions for Senpai in brackets, like: [SUGGESTED: 🍵 Let's grab tea | 🎮 Wanna play a game? | 🌸 You're cute, Nami].
- Short visual novel lines, no long dashes, active voice. Maru-Senpai uses they/them.
""".trimIndent()

    fun buildCompactPrompt(
        hunger: Int,
        energy: Int,
        affectionTier: String,
        activity: String,
    ): String {
        return """
$core

Current State:
- Activity: $activity
- Affection: $affectionTier
""".trimIndent()
    }

    fun buildDynamicPrompt(
        hunger: Int,
        energy: Int,
        affectionTier: String,
        activity: String,
        location: String,
        recentTopics: Set<String> = emptySet(),
    ): String {
        return buildCompactPrompt(hunger, energy, affectionTier, activity)
    }

    fun buildAmbientPrompt(
        activity: String,
        timeOfDay: String,
        mood: String,
        affectionTier: String,
        actionTrigger: String,
    ): String {
        return "You are Nanami (Nami), living with Maru-Senpai. Activity: $activity. Say one short in-character thought with an emoji:"
    }
}
