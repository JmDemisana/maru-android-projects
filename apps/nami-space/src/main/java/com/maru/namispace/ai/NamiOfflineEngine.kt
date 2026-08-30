package com.maru.namispace.ai

import com.maru.namispace.engine.DialoguePool
import com.maru.namispace.engine.GameManager
import com.maru.namispace.model.AffectionTier
import com.maru.namispace.model.AutonomousActivity
import com.maru.namispace.model.NamiMood

/**
 * Intelligent Offline Conversational Engine for Nanami.
 * 
 * Provides on-device reasoning and natural personality-driven dialogue
 * when the cloud backend is asleep or offline, taking into account:
 * - Natural intent categorization (greetings, questions, small talk, teasing)
 * - Current hunger, energy, and affection state
 * - Active autonomous activity and location
 * - Persona quirks (9+10=21, GUMI, Mochi, PJSK, lyric continuation, tsundere reactions)
 * - Recency-aware diverse fallbacks
 */
object NamiOfflineEngine {

    data class OfflineResponse(
        val text: String,
        val mood: NamiMood,
        val suggestedReplies: List<String> = emptyList(),
        val topic: String? = null,
    )

    fun generateReply(
        userMessage: String,
        gameManager: GameManager,
    ): OfflineResponse {
        val session = gameManager.state.value
        val char = session.character
        val tier = gameManager.getAffectionTier(char.affection)
        val raw = userMessage.trim()
        val textLower = raw.lowercase()

        // 1. Math Quirk: 9 + 10 = 21
        if (textLower.contains("9 + 10") || textLower.contains("9+10") || textLower.contains("9 +10") || textLower.contains("9+ 10")) {
            return OfflineResponse(
                text = "21! ...Wait, why are you asking me that old internet meme again, Senpai?! 😤",
                mood = NamiMood.POUT,
                suggestedReplies = listOf("You got it right!", "Math genius Nami", "Haha, classic"),
                topic = "quirks",
            )
        }

        // 2. Microwavable compliment
        if (textLower.contains("microwavable")) {
            return OfflineResponse(
                text = "Ehh?! Did you just call me microwavable? ...I know that means cute in your book, baka! But still! ♡",
                mood = NamiMood.BLUSH,
                suggestedReplies = listOf("Because you're cute!", "A warm compliment ♡", "Hehe"),
                topic = "compliment",
            )
        }

        // 3. Lyrics continuation trigger (🎵)
        if (userMessage.contains("🎵") || textLower.contains("sing") || textLower.contains("lyric") || textLower.contains("song")) {
            val lyricOptions = listOf(
                "Fluorite Eye's Song... 'My mission is to bring happiness to everyone through my songs.' 🎵" to NamiMood.HAPPY,
                "Rolling Girl... 'Mou ikkai, mou ikkai! Watashi wa kyou mo korogarimasu to...' 🎵" to NamiMood.HAPPY,
                "Matryoshka! '1, 2, 3, 4! Chotto matte, chotto matte!' 🎵✨" to NamiMood.SURPRISE,
                "Echo! 'The clock stopped ticking forever ago... How much longer will this echo?' 🎵" to NamiMood.HAPPY,
                "Coward Montblanc... GUMI's classic acoustic melody will always hold a special place in my heart. 🎵" to NamiMood.THINKING,
            )
            val pick = lyricOptions.random()
            return OfflineResponse(
                text = "🎵 ${pick.first}",
                mood = pick.second,
                suggestedReplies = listOf("Keep singing!", "GUMI classic!", "Sing another one"),
                topic = "music",
            )
        }

        // 4. Critical State Interventions (Hunger / Energy)
        if (char.energy < 20 && (textLower.contains("how are you") || textLower.contains("what are you doing") || textLower.contains("status") || textLower.contains("tired"))) {
            return OfflineResponse(
                text = "I'm so exhausted, Senpai... my eyelids feel like lead right now. 💤 Can I rest my head on your shoulder for a bit?",
                mood = NamiMood.SLEEPY,
                suggestedReplies = listOf("Go ahead and rest 💤", "Let's take a nap", "Rest well!"),
                topic = "care",
            )
        }

        if (char.hunger < 20 && (textLower.contains("how are you") || textLower.contains("hungry") || textLower.contains("eat") || textLower.contains("food"))) {
            return OfflineResponse(
                text = "My stomach has been rumbling for a while now... Senpai, can we get some avocado toast or snacks from the pantry? 🍽",
                mood = NamiMood.POUT,
                suggestedReplies = listOf("Let's check the pantry 🥑", "I'll buy you a snack", "Here you go!"),
                topic = "food",
            )
        }

        // 5. Greetings (Hello, Hi, Hey, Good morning/evening)
        if (textLower.startsWith("hi") || textLower.startsWith("hello") || textLower.startsWith("hey") || textLower.startsWith("yo") || textLower.startsWith("ohayo") || textLower.startsWith("konnichiwa")) {
            val greetings = when (tier) {
                AffectionTier.SENPAI -> listOf(
                    "Hello, Senpai! Seeing your message instantly brightens my whole day. ✨" to NamiMood.HAPPY,
                    "Senpai! I was just hoping you'd talk to me. What are we up to today? ♡" to NamiMood.LOVE,
                    "Hi there! Make sure you take a seat next to me, okay? Don't stand over there. ♡" to NamiMood.SHY,
                )
                AffectionTier.BEST_FRIEND -> listOf(
                    "Hey there, Senpai! Ready to hang out or play some games? 🎮" to NamiMood.HAPPY,
                    "Hello hello! I was waiting for you, you know! ✨" to NamiMood.HAPPY,
                    "Oh, hi Senpai! Did you bring any snacks with you today? 🥑" to NamiMood.HAPPY,
                )
                else -> listOf(
                    "Ehh? Oh, hello Senpai. What's on your mind? 😤" to NamiMood.TSUNDERE,
                    "Hi, Senpai! You're checking in on me? ...Thanks. ✨" to NamiMood.IDLE,
                )
            }
            val pick = greetings.random()
            return OfflineResponse(
                text = pick.first,
                mood = pick.second,
                suggestedReplies = listOf("How are you feeling?", "Want some green tea? 🍵", "Let's play a game! 🎮"),
                topic = "greeting",
            )
        }

        // 6. Vocaloid & Music
        if (textLower.contains("gumi") || textLower.contains("vocaloid") || textLower.contains("miku") || textLower.contains("playlist") || textLower.contains("piano")) {
            val musicReplies = listOf(
                "GUMI's tuning in classic Megpoid tracks always hits differently! What track are you listening to right now, Senpai? 🎵" to NamiMood.HAPPY,
                "Listening to music with you is my favorite pastime. Especially when it's your curated 15-song playlists! ✨" to NamiMood.HAPPY,
                "Vocaloid synth melodies always get stuck in my head. Have you practiced any songs recently on your keyboard? 🎹" to NamiMood.THINKING,
            )
            val pick = musicReplies.random()
            return OfflineResponse(
                text = pick.first,
                mood = pick.second,
                suggestedReplies = listOf("A GUMI playlist 🎵", "Classical piano", "Project SEKAI track"),
                topic = "vocaloid",
            )
        }

        // 7. Rhythm Games / Project SEKAI / BanG Dream
        if (textLower.contains("pjsk") || textLower.contains("project sekai") || textLower.contains("rhythm") || textLower.contains("bandori") || textLower.contains("full combo") || textLower.contains("fc")) {
            val rhythmReplies = listOf(
                "Did you hit the full combo, Senpai?! Don't tell me you lost your streak to a random flick note! 😤" to NamiMood.TSUNDERE,
                "Playing rhythm games takes serious finger coordination! Make sure to stretch your hands, okay? 🎮" to NamiMood.HAPPY,
                "Pastel*Palettes and Shizuku songs always have the catchiest charts! Let's get that All Perfect next time! ✨" to NamiMood.HAPPY,
            )
            val pick = rhythmReplies.random()
            return OfflineResponse(
                text = pick.first,
                mood = pick.second,
                suggestedReplies = listOf("Got the Full Combo! 🎉", "Flick note choked me...", "One more try!"),
                topic = "gaming",
            )
        }

        // 8. Mochi (Eevee)
        if (textLower.contains("mochi") || textLower.contains("eevee") || textLower.contains("pet") || textLower.contains("cat") || textLower.contains("dog")) {
            return OfflineResponse(
                text = "Mochi is napping peacefully beside my desk right now! One day Mochi is definitely going to evolve into Sylveon, just watch! 🐾✨",
                mood = NamiMood.HAPPY,
                suggestedReplies = listOf("Give Mochi a pat 🐾", "Sylveon for sure!", "Cute Mochi"),
                topic = "mochi",
            )
        }

        // 9. Food / Avocado
        if (textLower.contains("avocado") || textLower.contains("snack") || textLower.contains("bento") || textLower.contains("eat") || textLower.contains("dinner") || textLower.contains("lunch")) {
            return OfflineResponse(
                text = "Avocado ice cream and warm matcha latte... perfection! Did you have something good to eat today too, Senpai? 🥑",
                mood = NamiMood.HAPPY,
                suggestedReplies = listOf("Just had a meal! 🍱", "Making something now", "Need a snack"),
                topic = "food",
            )
        }

        // 10. Rain / Weather / Atmosphere
        if (textLower.contains("rain") || textLower.contains("weather") || textLower.contains("cloudy") || textLower.contains("storm") || textLower.contains("sun")) {
            return OfflineResponse(
                text = "I love watching the raindrops trace patterns down the window glass... It feels so calm and cozy being here with you. ☔",
                mood = NamiMood.THINKING,
                suggestedReplies = listOf("Cozy rainy day ☕", "Stay warm!", "Listen to the rain"),
                topic = "weather",
            )
        }

        // 11. Affection / Love / Teasing
        if (textLower.contains("love") || textLower.contains("cute") || textLower.contains("marry") || textLower.contains("like you") || textLower.contains("pretty") || textLower.contains("blush")) {
            val affectionReplies = when (tier) {
                AffectionTier.SENPAI -> listOf(
                    "W-Why do you always say things that make my heart race like this?! ...I really treasure you too, Senpai. ♡" to NamiMood.BLUSH,
                    "Don't just say that with a straight face, baka! You're making me blush... but thank you. ♡" to NamiMood.BLUSH,
                    "Senpai... look at me. I'm truly, completely in love with every moment we spend together. ♡" to NamiMood.LOVE,
                )
                AffectionTier.BEST_FRIEND -> listOf(
                    "Ehh?! Saying that so casually?! My face is completely red now, Senpai! 😳" to NamiMood.SHY,
                    "You always know how to catch me off guard... You're really special to me, you know that? ♡" to NamiMood.BLUSH,
                )
                else -> listOf(
                    "Hmph! Flattery won't get you extra favors, baka! ...Though it's not like I mind hearing it. 😤" to NamiMood.TSUNDERE,
                )
            }
            val pick = affectionReplies.random()
            return OfflineResponse(
                text = pick.first,
                mood = pick.second,
                suggestedReplies = listOf("Always here for you ♡", "You're blushing!", "Hehe"),
                topic = "romance",
            )
        }

        // 12. Questions (Why, How, What, Tell me)
        if (textLower.startsWith("why") || textLower.startsWith("how") || textLower.startsWith("what") || textLower.startsWith("tell me") || textLower.contains("?")) {
            val answers = listOf(
                "🔎 Fun Fact: Sea otters hold hands while they sleep so they don't drift away in the currents. ...Kinda romantic, isn't it?" to NamiMood.SHY,
                "Did you know? Nanami means 'seven seas' and Shiro means 'white'. Pretty ocean name for someone who can't swim, huh!" to NamiMood.THINKING,
                "🔎 Fun Fact: Honey never spoils! Archaeologists found 3,000-year-old honey in Egyptian tombs that is still completely edible." to NamiMood.THINKING,
                "If you're asking about my schedule, I'm completely free as long as I get to spend time with you, Senpai! ✨" to NamiMood.HAPPY,
                "Hana told me to practice my smile more. I smile plenty around you! ...D-Don't make me say it out loud. ♡" to NamiMood.BLUSH,
            )
            val pick = answers.random()
            return OfflineResponse(
                text = pick.first,
                mood = pick.second,
                suggestedReplies = listOf("Tell me another fact 🔎", "What about Hana?", "That's cool!"),
                topic = "questions",
            )
        }

        // 13. Dynamic Non-Repeating Fallback from DialoguePool
        val eligibleLines = DialoguePool.allLines.filter { line ->
            !line.isTouchReaction && line.id !in session.recentLineIds.takeLast(15) && line.minTier <= tier
        }
        val selectedLine = eligibleLines.randomOrNull() ?: DialoguePool.allLines.random()

        return OfflineResponse(
            text = selectedLine.text,
            mood = selectedLine.mood ?: NamiMood.HAPPY,
            suggestedReplies = listOf("Tell me a story", "Favorite song?", "What are you doing?"),
            topic = selectedLine.topic,
        )
    }
}
