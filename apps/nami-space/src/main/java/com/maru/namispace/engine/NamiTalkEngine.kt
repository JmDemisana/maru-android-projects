package com.maru.namispace.engine

import com.maru.namispace.model.NamiMood
import com.maru.namispace.model.StoryChapter
import com.maru.namispace.model.StoryNode

/**
 * Manages story playback state — which node we're on, choices, branching.
 */
class NamiTalkEngine {

    private var currentChapter: StoryChapter? = null
    private var currentNodeId: String? = null
    private var visitedNodes = mutableSetOf<String>()

    fun startChapter(chapter: StoryChapter) {
        currentChapter = chapter
        currentNodeId = chapter.nodes.firstOrNull()?.id
        visitedNodes.clear()
    }

    fun getCurrentNode(): StoryNode? {
        val chapter = currentChapter ?: return null
        val nodeId = currentNodeId ?: return null
        return chapter.nodes.find { it.id == nodeId }
    }

    fun advance(): StoryNode? {
        val node = getCurrentNode() ?: return null
        visitedNodes.add(node.id)

        // Auto-advance if no choices
        if (node.choices.isEmpty() && node.nextNodeId != null) {
            currentNodeId = node.nextNodeId
        }

        return getCurrentNode()
    }

    fun selectChoice(choiceIndex: Int): Pair<StoryNode?, Int> {
        val node = getCurrentNode() ?: return Pair(null, 0)
        val choice = node.choices.getOrNull(choiceIndex) ?: return Pair(null, 0)

        visitedNodes.add(node.id)
        currentNodeId = choice.nextNodeId

        return Pair(getCurrentNode(), choice.affectionBonus)
    }

    fun isChoiceAvailable(choiceIndex: Int, requiredAffection: Int): Boolean {
        val node = getCurrentNode() ?: return false
        val choice = node.choices.getOrNull(choiceIndex) ?: return false
        return requiredAffection >= choice.requiredAffection
    }

    fun isChapterComplete(): Boolean {
        val node = getCurrentNode()
        return node == null || (node.choices.isEmpty() && node.nextNodeId == null)
    }

    fun getChapterProgress(): Float {
        val chapter = currentChapter ?: return 0f
        val total = chapter.nodes.size
        val visited = visitedNodes.size
        return visited.toFloat() / total.coerceAtLeast(1)
    }
}
