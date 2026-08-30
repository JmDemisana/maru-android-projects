package com.maru.namispace.model

data class AffectionState(
    val level: Int = 0,
    val totalMessages: Int = 0,
    val daysSinceInstall: Int = 0,
    val topicsDiscussed: Set<String> = emptySet(),
    val storiesUnlocked: Set<String> = emptySet(),
) {
    val tier: AffectionTier
        get() = when {
            level >= 50 -> AffectionTier.SENPAI
            level >= 30 -> AffectionTier.BEST_FRIEND
            level >= 15 -> AffectionTier.CLOSE_FRIEND
            level >= 5  -> AffectionTier.FRIEND
            else        -> AffectionTier.STRANGER
        }
}

enum class AffectionTier(val label: String) {
    STRANGER("Polite Stranger"),
    FRIEND("Friend"),
    CLOSE_FRIEND("Close Friend"),
    BEST_FRIEND("Best Friend"),
    SENPAI("Senpai");
}
