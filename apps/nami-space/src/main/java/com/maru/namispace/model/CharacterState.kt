package com.maru.namispace.model

data class CharacterState(
    val mood: NamiMood = NamiMood.IDLE,
    val hunger: Int = 70,
    val energy: Int = 80,
    val affection: Int = 0,
    val lastAiDialogueTimestamp: Long = 0L,
) {
    val effectiveMood: NamiMood
        get() = when {
            hunger < 20 -> NamiMood.SAD
            energy < 15 -> NamiMood.SLEEPY
            affection >= 50 && mood == NamiMood.IDLE -> NamiMood.LOVE
            else -> mood
        }
}
