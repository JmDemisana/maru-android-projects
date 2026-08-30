package com.maru.namispace.model

data class StoryChapter(
    val id: String,
    val title: String,
    val description: String,
    val requiredAffection: Int = 0,
    val requiredTopics: List<String> = emptyList(),
    val nodes: List<StoryNode>,
)

data class StoryNode(
    val id: String,
    val speaker: String,       // "nami", "hana", "narrator"
    val text: String,
    val mood: NamiMood = NamiMood.IDLE,
    val choices: List<StoryChoice> = emptyList(),
    val nextNodeId: String? = null,  // auto-advance to next node
)

data class StoryChoice(
    val text: String,
    val nextNodeId: String,
    val affectionBonus: Int = 0,
    val requiredAffection: Int = 0,
)
