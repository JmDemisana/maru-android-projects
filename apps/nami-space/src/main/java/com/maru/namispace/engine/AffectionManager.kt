package com.maru.namispace.engine

import com.maru.namispace.model.AffectionState
import com.maru.namispace.model.AffectionTier
import com.maru.namispace.model.NamiMood

class AffectionManager {

    private var state = AffectionState()

    fun getState(): AffectionState = state

    fun addAffection(amount: Int) {
        state = state.copy(level = (state.level + amount).coerceAtMost(100))
    }

    fun incrementMessages() {
        state = state.copy(totalMessages = state.totalMessages + 1)
        if (state.totalMessages % 10 == 0) {
            addAffection(1)
        }
    }

    fun addTopic(topic: String) {
        state = state.copy(topicsDiscussed = state.topicsDiscussed + topic)
    }

    fun unlockStory(storyId: String) {
        state = state.copy(storiesUnlocked = state.storiesUnlocked + storyId)
    }

    fun updateDays(days: Int) {
        state = state.copy(daysSinceInstall = days)
        if (days > state.daysSinceInstall) {
            addAffection(1)
        }
    }

    fun suggestedMood(): NamiMood {
        return when (state.tier) {
            AffectionTier.STRANGER -> NamiMood.IDLE
            AffectionTier.FRIEND -> {
                if (state.totalMessages % 3 == 0) NamiMood.HAPPY
                else NamiMood.IDLE
            }
            AffectionTier.CLOSE_FRIEND -> NamiMood.randomFriendly()
            AffectionTier.BEST_FRIEND -> {
                listOf(NamiMood.HAPPY, NamiMood.SHY, NamiMood.THINKING, NamiMood.IDLE).random()
            }
            AffectionTier.SENPAI -> {
                listOf(NamiMood.HAPPY, NamiMood.TSUNDERE, NamiMood.SHY, NamiMood.BLUSH, NamiMood.IDLE).random()
            }
        }
    }
}
