package com.maru.namispace.engine

import android.content.Context
import com.maru.namispace.data.PrefsManager
import com.maru.namispace.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GameManager(context: Context) {

    private val prefs = PrefsManager(context)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<GameSession> = _state.asStateFlow()

    data class GameSession(
        val character: CharacterState = CharacterState(),
        val currency: CurrencyState = CurrencyState(),
        val inventory: Map<String, Int> = emptyMap(),
        val dialogueLines: List<String> = emptyList(),
        val currentDialogue: String = "",
        val topicsDiscussed: Set<String> = emptySet(),
        val completedStories: Set<String> = emptySet(),
        val recentLineIds: List<String> = emptyList(),
        val isAiDialogueLoading: Boolean = false,
        val lastAiDialogueTimestamp: Long = 0L,
        val bgmMuted: Boolean = false,
        // Autonomous & Lifecycle
        val currentActivity: AutonomousActivity = AutonomousActivity.STUDYING,
        val locationMode: LocationMode = LocationMode.HOME,
        val saidGoodbye: Boolean = false,
        val lastActiveTimestamp: Long = System.currentTimeMillis(),
        // Outing
        val outingDistanceMeters: Float = 0f,
        val collectedSouvenirs: Set<String> = emptySet(),
        val pendingEncounter: OutingEncounter? = null,
        // AI Suggested Replies
        val suggestedReplies: List<String> = emptyList(),
    )

    fun setSuggestedReplies(chips: List<String>) {
        if (chips.isEmpty()) return
        _state.update { it.copy(suggestedReplies = chips) }
        saveState()
    }

    fun consumeItem(itemId: String) {
        val item = ShopCatalog.getItem(itemId) ?: return
        val (reactionText, reactionMood) = DialoguePool.getItemReactionDialogue(itemId)

        _state.update { session ->
            val currentCount = session.inventory[itemId] ?: 0
            if (currentCount <= 0) return@update session

            val char = session.character
            session.copy(
                character = char.copy(
                    hunger = (char.hunger + item.hungerRestore).coerceAtMost(100),
                    energy = (char.energy + item.energyRestore).coerceAtMost(100),
                    affection = (char.affection + item.affectionBonus).coerceAtMost(100),
                    mood = reactionMood,
                ),
                inventory = session.inventory + (itemId to (currentCount - 1)),
                currentDialogue = reactionText,
            )
        }
        saveState()
    }

    fun buyItem(itemId: String): Boolean {
        val item = ShopCatalog.getItem(itemId) ?: return false
        var success = false
        _state.update { session ->
            if (session.currency.coins < item.price) return@update session
            success = true
            session.copy(
                currency = session.currency.copy(
                    coins = session.currency.coins - item.price,
                    totalSpent = session.currency.totalSpent + item.price,
                ),
                inventory = session.inventory + (itemId to ((session.inventory[itemId] ?: 0) + 1)),
            )
        }
        if (success) saveState()
        return success
    }

    fun earnCoins(amount: Int, reason: String) {
        _state.update { session ->
            session.copy(
                currency = session.currency.copy(
                    coins = session.currency.coins + amount,
                    totalEarned = session.currency.totalEarned + amount,
                ),
                dialogueLines = session.dialogueLines + reason,
            )
        }
        saveState()
    }

    fun spendCoins(amount: Int): Boolean {
        if (amount <= 0) return true
        if (_state.value.currency.coins < amount) return false
        _state.update { session ->
            session.copy(
                currency = session.currency.copy(
                    coins = (session.currency.coins - amount).coerceAtLeast(0),
                    totalSpent = session.currency.totalSpent + amount,
                ),
            )
        }
        saveState()
        return true
    }

    fun addChatAffection() {
        _state.update { session ->
            val newTotal = session.character.affection + 1
            val coinBonus = if (newTotal % 10 == 0) 1 else 0
            session.copy(
                character = session.character.copy(
                    affection = newTotal.coerceAtMost(100),
                ),
                currency = if (coinBonus > 0) {
                    session.currency.copy(
                        coins = session.currency.coins + coinBonus,
                        totalEarned = session.currency.totalEarned + coinBonus,
                    )
                } else session.currency,
            )
        }
        saveState()
    }

    /** Meaningful Tamagotchi & dating sim companion activity */
    fun spendTime(activity: CompanionActivity) {
        val (reactionText, reactionMood) = DialoguePool.getActivityReaction(activity)

        _state.update { session ->
            val char = session.character
            val affectionGain = if (char.affection < 100) activity.affectionGain else 0
            session.copy(
                character = char.copy(
                    hunger = (char.hunger + activity.hungerRestore).coerceIn(0, 100),
                    energy = (char.energy + activity.energyRestore).coerceIn(0, 100),
                    affection = (char.affection + affectionGain).coerceIn(0, 100),
                    mood = reactionMood,
                ),
                currency = session.currency.copy(
                    coins = session.currency.coins + activity.coins,
                    totalEarned = session.currency.totalEarned + activity.coins,
                ),
                currentDialogue = reactionText,
            )
        }
        saveState()
    }

    /** Tell Nanami to rest and recover her energy */
    fun tellNamiToRest() {
        _state.update { session ->
            val char = session.character
            val restText = when {
                char.energy < 20 -> "I was barely keeping my eyes open... okay. I'll rest for a bit. Don't go anywhere, Senpai. 💤"
                char.energy < 50 -> "Mhm... I am a little tired. A short nap sounds nice actually. Wake me up soon? 🌙"
                else -> "I'm not even that tired, but... if Senpai insists. I'll take a small nap. Don't peek. 😤"
            }
            session.copy(
                character = char.copy(
                    energy = (char.energy + 35).coerceAtMost(100),
                    mood = NamiMood.SLEEPY,
                ),
                currentActivity = AutonomousActivity.NAPPING,
                currentDialogue = restText,
            )
        }
        saveState()
    }

    fun recordTopic(topic: String?) {
        val normalized = topic?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return
        _state.update { it.copy(topicsDiscussed = it.topicsDiscussed + normalized) }
        saveState()
    }

    fun resolveStoryChoice(affectionBonus: Int, dialogue: String, mood: NamiMood) {
        _state.update { session ->
            session.copy(
                character = session.character.copy(
                    affection = (session.character.affection + affectionBonus).coerceAtMost(100),
                    mood = mood,
                ),
                currentDialogue = dialogue,
            )
        }
        saveState()
    }

    fun completeStory(storyId: String, closingLine: String, mood: NamiMood) {
        _state.update { session ->
            if (storyId in session.completedStories) session else session.copy(
                completedStories = session.completedStories + storyId,
                character = session.character.copy(
                    affection = (session.character.affection + 3).coerceAtMost(100),
                    mood = mood,
                ),
                currency = session.currency.copy(
                    coins = session.currency.coins + 5,
                    totalEarned = session.currency.totalEarned + 5,
                ),
                currentDialogue = closingLine,
            )
        }
        saveState()
    }

    fun getAffectionTier(affection: Int): AffectionTier {
        return when {
            affection >= 80 -> AffectionTier.SENPAI
            affection >= 50 -> AffectionTier.BEST_FRIEND
            affection >= 25 -> AffectionTier.CLOSE_FRIEND
            affection >= 10 -> AffectionTier.FRIEND
            else -> AffectionTier.STRANGER
        }
    }

    fun tickStats() {
        _state.update { session ->
            val char = session.character
            val newHunger = (char.hunger - 1).coerceAtLeast(0)
            val newEnergy = (char.energy - 1).coerceAtLeast(0)
            
            var newActivity = session.currentActivity
            var newMood = char.mood
            var newDialogue = session.currentDialogue

            // Autonomous State Changes based on Stats
            if (newEnergy < 20 && session.currentActivity != AutonomousActivity.NAPPING) {
                newActivity = AutonomousActivity.NAPPING
                newMood = NamiMood.SLEEPY
                newDialogue = "I'm so tired... I'm just going to rest my eyes for a bit... 💤"
            } else if (newHunger < 20 && session.currentActivity != AutonomousActivity.NAPPING) {
                newMood = NamiMood.POUT
                if (char.hunger >= 20) { // Only say it once when crossing the threshold
                    newDialogue = "My stomach is growling... Senpai, can we get something to eat from the pantry? 🍽"
                }
            }

            session.copy(
                character = char.copy(
                    hunger = newHunger,
                    energy = newEnergy,
                    mood = newMood,
                ),
                currentActivity = newActivity,
                currentDialogue = newDialogue,
            )
        }
        saveState()
    }

    fun setMood(mood: NamiMood) {
        _state.update { session ->
            session.copy(character = session.character.copy(mood = mood))
        }
        saveState()
    }

    fun setDialogue(text: String, mood: NamiMood = NamiMood.IDLE) {
        _state.update { session ->
            session.copy(
                currentDialogue = text,
                character = session.character.copy(mood = mood),
            )
        }
        saveState()
    }

    fun updateDialogue(text: String) {
        _state.update { it.copy(currentDialogue = text) }
    }

    /** Advances dialogue using weighted selection with recency decay penalty */
    fun refreshMoodDialogue() {
        val session = _state.value
        val char = session.character
        val tier = getAffectionTier(char.affection)

        val line = DialoguePool.selectWeightedDialogue(
            mood = char.effectiveMood,
            hunger = char.hunger,
            energy = char.energy,
            tier = tier,
            recentLineIds = session.recentLineIds,
            isTouch = false,
        )

        _state.update {
            it.copy(
                currentDialogue = line.text,
                character = it.character.copy(mood = line.mood ?: it.character.mood),
                recentLineIds = (it.recentLineIds + line.id).takeLast(25),
            )
        }
        saveState()
    }

    private val localEngine = com.maru.namispace.ai.NamiLocalChatEngine(context)

    /**
     * Generates a dynamic unscripted ambient thought on-device and updates dialogue.
     */
    suspend fun generateDynamicThought(actionTrigger: String = "Senpai glances over at you") {
        val session = _state.value
        val char = session.character
        val tier = getAffectionTier(char.affection)
        val thought = localEngine.generateAmbientThought(
            activity = session.currentActivity,
            affectionTier = tier,
            mood = char.effectiveMood,
            actionTrigger = actionTrigger
        )
        if (thought.isNotBlank()) {
            _state.update { it.copy(currentDialogue = thought) }
            saveState()
        }
    }

    /** Interactive touch/tap on Nami's character with weighted touch reactions */
    fun interactWithNami(scope: kotlinx.coroutines.CoroutineScope? = null) {
        val session = _state.value
        val char = session.character
        val tier = getAffectionTier(char.affection)

        val line = DialoguePool.selectWeightedDialogue(
            mood = char.effectiveMood,
            hunger = char.hunger,
            energy = char.energy,
            tier = tier,
            recentLineIds = session.recentLineIds,
            isTouch = true,
        )

        _state.update {
            it.copy(
                character = it.character.copy(mood = line.mood ?: it.character.mood),
                currentDialogue = line.text,
                recentLineIds = (it.recentLineIds + line.id).takeLast(25),
            )
        }
        saveState()

        // Generate unscripted neural thought in background
        if (scope != null && localEngine.isReady) {
            scope.launch {
                generateDynamicThought("Senpai playfully taps on you")
            }
        }
    }

    fun toggleBgmMute() {
        _state.update { it.copy(bgmMuted = !it.bgmMuted) }
        saveState()
    }

    // =====================================================================
    // LIFECYCLE: Resume (checks goodbye state and absence duration)
    // =====================================================================
    fun onAppResume() {
        val session = _state.value
        val now = System.currentTimeMillis()
        val absenceMs = now - session.lastActiveTimestamp
        val absenceMinutes = absenceMs / 60_000L

        // Only show absence dialogue if gone >= 1 minute
        if (absenceMinutes >= 1) {
            val char = session.character
            val tier = getTier(char.affection)
            val line = DialoguePool.selectAbsenceDialogue(
                saidGoodbye = session.saidGoodbye,
                absenceMinutes = absenceMinutes,
                tier = tier,
            )

            // Apply offline hunger/energy progression
            val hungerDrop = ((absenceMinutes / 30) * 5).coerceAtMost(40).toInt()
            val energyRestore = if (session.currentActivity == AutonomousActivity.NAPPING)
                ((absenceMinutes / 60) * 15).coerceAtMost(50).toInt() else 0

            _state.update { s ->
                s.copy(
                    currentDialogue = line.text,
                    character = s.character.copy(
                        mood = line.mood ?: NamiMood.IDLE,
                        hunger = (s.character.hunger - hungerDrop).coerceAtLeast(5),
                        energy = (s.character.energy + energyRestore).coerceAtMost(100),
                    ),
                    saidGoodbye = false, // Reset after greeting
                    lastActiveTimestamp = now,
                )
            }
        } else {
            _state.update { it.copy(lastActiveTimestamp = now, saidGoodbye = false) }
        }

        // Also refresh the autonomous activity
        pickNewActivity()
        saveState()
    }

    // =====================================================================
    // LIFECYCLE: Pause (record timestamp, do NOT mark saidGoodbye here)
    // =====================================================================
    fun onAppPause() {
        _state.update { it.copy(lastActiveTimestamp = System.currentTimeMillis()) }
        saveState()
    }

    // =====================================================================
    // GOODBYE: Called when user taps "See You Later, Nami!"
    // =====================================================================
    fun sayGoodbye() {
        _state.update { session ->
            session.copy(
                saidGoodbye = true,
                currentDialogue = "Okay, Senpai! See you soon! Don't forget to eat dinner! ♡",
                character = session.character.copy(mood = NamiMood.HAPPY),
                lastActiveTimestamp = System.currentTimeMillis(),
            )
        }
        saveState()
    }

    // =====================================================================
    // AUTONOMOUS ACTIVITY: Pick new activity based on stats
    // =====================================================================
    fun pickNewActivity() {
        val char = _state.value.character
        val activity = AutonomousActivity.pickForState(char.hunger, char.energy, char.effectiveMood)
        val line = DialoguePool.selectActivityDialogue(activity)
        _state.update { session ->
            session.copy(
                currentActivity = activity,
                character = session.character.copy(mood = activity.defaultMood),
                currentDialogue = line.text,
            )
        }
        prefs.currentActivityId = activity.id
    }

    // =====================================================================
    // LOCATION MODE: Switch between Home, School/Work, Outing
    // =====================================================================
    fun setLocationMode(mode: LocationMode) {
        val line = when (mode) {
            LocationMode.HOME -> "I'm so glad you're back home, Senpai! Let's relax together. 🏠"
            LocationMode.SCHOOL_WORK -> DialoguePool.selectQuietStudyDialogue().text
            LocationMode.OUTING -> "An outing?! Let's go, Senpai! I'll grab my bag! 🚶‍♀️✨"
        }
        val mood = when (mode) {
            LocationMode.HOME -> NamiMood.HAPPY
            LocationMode.SCHOOL_WORK -> NamiMood.THINKING
            LocationMode.OUTING -> NamiMood.HAPPY
        }
        _state.update { session ->
            session.copy(
                locationMode = mode,
                currentDialogue = line,
                character = session.character.copy(mood = mood),
                outingDistanceMeters = if (mode == LocationMode.OUTING) 0f else session.outingDistanceMeters,
            )
        }
        prefs.locationMode = mode.id
        saveState()
    }

    // =====================================================================
    // OUTING: Add GPS distance, check for encounters
    // =====================================================================
    fun addOutingDistance(newMeters: Float) {
        val session = _state.value
        if (session.pendingEncounter != null) return // Don't stack encounters

        val totalDist = session.outingDistanceMeters + newMeters
        val milestone = (totalDist / 200).toInt() // Every 200m check for encounter
        val prevMilestone = (session.outingDistanceMeters / 200).toInt()

        val encounter = if (milestone > prevMilestone && Math.random() < 0.5) {
            OutingCatalog.encounters
                .filter { it.id !in session.collectedSouvenirs || it.souvenir == null }
                .randomOrNull()
        } else null

        _state.update { s ->
            s.copy(
                outingDistanceMeters = totalDist,
                pendingEncounter = encounter ?: s.pendingEncounter,
            )
        }
        prefs.outingDistanceMeters = totalDist
    }

    fun claimEncounter(encounter: OutingEncounter) {
        _state.update { session ->
            session.copy(
                pendingEncounter = null,
                currentDialogue = "Oh! ${encounter.title}! ${encounter.description} ✨",
                character = session.character.copy(
                    mood = NamiMood.HAPPY,
                    affection = (session.character.affection + encounter.affectionBonus).coerceAtMost(100),
                ),
                currency = session.currency.copy(
                    coins = session.currency.coins + encounter.coinsReward,
                    totalEarned = session.currency.totalEarned + encounter.coinsReward,
                ),
                collectedSouvenirs = if (encounter.souvenir != null) {
                    session.collectedSouvenirs + encounter.id
                } else session.collectedSouvenirs,
            )
        }
        prefs.collectedSouvenirs = _state.value.collectedSouvenirs
        saveState()
    }

    fun dismissEncounter() {
        _state.update { it.copy(pendingEncounter = null) }
    }

    private fun getTier(affection: Int) = when {
        affection >= 50 -> AffectionTier.SENPAI
        affection >= 30 -> AffectionTier.BEST_FRIEND
        affection >= 15 -> AffectionTier.CLOSE_FRIEND
        affection >= 5 -> AffectionTier.FRIEND
        else -> AffectionTier.STRANGER
    }

    private fun loadState(): GameSession {
        val activityId = prefs.currentActivityId
        val activity = AutonomousActivity.entries.firstOrNull { it.id == activityId }
            ?: AutonomousActivity.STUDYING
        val locationId = prefs.locationMode
        val locationMode = LocationMode.entries.firstOrNull { it.id == locationId }
            ?: LocationMode.HOME

        return GameSession(
            character = CharacterState(
                mood = NamiMood.IDLE,
                hunger = prefs.hunger,
                energy = prefs.energy,
                affection = prefs.affectionLevel,
            ),
            currency = CurrencyState(
                coins = prefs.coins,
                totalEarned = prefs.totalCoinsEarned,
            ),
            inventory = prefs.inventory,
            currentDialogue = prefs.lastDialogue.ifBlank {
                "Welcome back, Senpai. It's really nice to see you today."
            },
            topicsDiscussed = prefs.topicsDiscussed,
            completedStories = prefs.storiesUnlocked,
            recentLineIds = prefs.recentLineIds,
            lastAiDialogueTimestamp = prefs.lastAiDialogueTimestamp,
            bgmMuted = prefs.bgmMuted,
            currentActivity = activity,
            locationMode = locationMode,
            saidGoodbye = prefs.saidGoodbye,
            lastActiveTimestamp = prefs.lastActiveTimestamp,
            outingDistanceMeters = prefs.outingDistanceMeters,
            collectedSouvenirs = prefs.collectedSouvenirs,
            suggestedReplies = prefs.suggestedReplies,
        )
    }

    private fun saveState() {
        val session = _state.value
        prefs.hunger = session.character.hunger
        prefs.energy = session.character.energy
        prefs.affectionLevel = session.character.affection
        prefs.coins = session.currency.coins
        prefs.totalCoinsEarned = session.currency.totalEarned
        prefs.inventory = session.inventory
        prefs.lastDialogue = session.currentDialogue
        prefs.topicsDiscussed = session.topicsDiscussed
        prefs.storiesUnlocked = session.completedStories
        prefs.recentLineIds = session.recentLineIds
        prefs.lastAiDialogueTimestamp = session.lastAiDialogueTimestamp
        prefs.bgmMuted = session.bgmMuted
        prefs.saidGoodbye = session.saidGoodbye
        prefs.lastActiveTimestamp = session.lastActiveTimestamp
        prefs.currentActivityId = session.currentActivity.id
        prefs.locationMode = session.locationMode.id
        prefs.outingDistanceMeters = session.outingDistanceMeters
        prefs.suggestedReplies = session.suggestedReplies
    }
}

enum class CompanionActivity(
    val label: String,
    val icon: String,
    val hungerRestore: Int = 0,
    val energyRestore: Int = 0,
    val affectionGain: Int = 1,
    val coins: Int = 0,
) {
    CHAT("Hang Out", "💬", energyRestore = 10, affectionGain = 1, coins = 0),
    DATE("Mini-Date", "✦", energyRestore = 15, affectionGain = 2, coins = 1),
    STUDY("Study", "📚", energyRestore = 15, affectionGain = 1, coins = 1),
    PAT("Head Pat", "撫", affectionGain = 1, coins = 0),
}
