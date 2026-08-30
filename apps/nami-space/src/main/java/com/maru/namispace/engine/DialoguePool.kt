package com.maru.namispace.engine

import com.maru.namispace.model.AffectionTier
import com.maru.namispace.model.NamiMood
import java.util.Calendar
import kotlin.math.max
import kotlin.random.Random

object DialoguePool {

    data class Line(
        val id: String,
        val text: String,
        val mood: NamiMood? = null,
        val topic: String = "general",
        val minTier: AffectionTier = AffectionTier.STRANGER,
        val timeOfDay: TimeOfDay? = null,
        val isTouchReaction: Boolean = false,
    )

    enum class TimeOfDay {
        MORNING,
        AFTERNOON,
        EVENING,
        NIGHT,
    }

    // Comprehensive authentic NamiTalk dialogue corpus
    val allLines: List<Line> = listOf(
        // === IDLE / DAILY (General & Lore) ===
        Line("idle_01", "The wind feels nice today... I like quiet days like this with you, Senpai.", NamiMood.IDLE, "weather"),
        Line("idle_02", "Senpai, are you just going to stand there? Come sit beside me.", NamiMood.SHY, "daily", minTier = AffectionTier.FRIEND),
        Line("idle_03", "I made some green tea earlier. Want some? ...I only made enough for the two of us, that's all.", NamiMood.TSUNDERE, "food"),
        Line("idle_04", "Mochi keeps staring at me with those big Eevee eyes. I think she wants treats again.", NamiMood.HAPPY, "mochi"),
        Line("idle_05", "Did you know? Nanami means 'seven seas' and Shiro means 'white'. Pretty ocean name for someone who can't swim, huh.", NamiMood.THINKING, "lore"),
        Line("idle_06", "Hana texted me again asking if I made you food. Why is my little sister so nosy?!", NamiMood.TSUNDERE, "hana", minTier = AffectionTier.CLOSE_FRIEND),
        Line("idle_07", "I found a four-leaf clover near the bus stop today. I'm pressing it inside my diary.", NamiMood.HAPPY, "daily"),
        Line("idle_08", "The classroom is so peaceful once everyone goes home. Just... the sunset and us.", NamiMood.THINKING, "daily", minTier = AffectionTier.FRIEND),
        Line("idle_09", "Senpai, you're staring at my hair again. ...I-Is the ribbon crooked or something?", NamiMood.BLUSH, "senpai"),
        Line("idle_10", "I was up late putting together a new Vocaloid playlist. GUMI's classic songs are timeless.", NamiMood.HAPPY, "gumi"),
        Line("idle_11", "The forecast says rain later. I brought my green umbrella. ...Just one umbrella.", NamiMood.SHY, "weather", minTier = AffectionTier.CLOSE_FRIEND),
        Line("idle_12", "Bear-chan was peeking in the background of my photo again! How does that bear keep showing up?!", NamiMood.PANIC, "bear-chan"),
        Line("idle_13", "I tried making avocado toast this morning. It turned out super crispy! I saved half for you.", NamiMood.HAPPY, "avocado", minTier = AffectionTier.FRIEND),
        Line("idle_14", "Do you think Mochi dreams about becoming a Sylveon or an Espeon? I hope she stays cozy either way.", NamiMood.THINKING, "mochi"),
        Line("idle_15", "Sometimes I catch myself humming Fluorite Eye's Song when the room gets quiet.", NamiMood.SHY, "music"),
        Line("idle_16", "Senpai, you are surprisingly microwavable today. ...Don't ask what that means, it's a compliment!", NamiMood.TSUNDERE, "senpai"),
        Line("idle_17", "Hana told me to practice my smile more. I smile plenty around you! ...D-Don't make me say it out loud.", NamiMood.BLUSH, "hana", minTier = AffectionTier.CLOSE_FRIEND),
        Line("idle_18", "A calico cat was sitting on the fence outside. It looked like it had very important business to attend to.", NamiMood.THINKING, "daily"),
        Line("idle_19", "I reorganized my bookshelf yesterday by color gradient. Now I can't find anything, but it looks aesthetic.", NamiMood.HAPPY, "daily"),
        Line("idle_20", "Senpai, what is 9 + 10? ...If you say 21, I'm taking away your tea privileges!", NamiMood.POUT, "senpai"),

        // === VOCALOID / MUSIC / SENPAI INTERESTS (NamiTalk Reference) ===
        Line("music_01", "GUMI's vocals in 'Coward Montblanc' and 'Echo' always hit right in the heart. What's your favorite GUMI track, Senpai?", NamiMood.HAPPY, "gumi"),
        Line("music_02", "Classic Hatsune Miku tracks have that nostalgic retro charm. 'World is Mine' will never not be iconic.", NamiMood.HAPPY, "miku"),
        Line("music_03", "Do you ever play Colorful Stage with thumb controls on Master difficulty? My fingers get tangled on 30+ charts!", NamiMood.SURPRISE, "colorful stage"),
        Line("music_04", "Listening to Mozart's Clarinet Concerto or Bach's Air on the G String while it rains outside is pure tranquility.", NamiMood.THINKING, "music"),
        Line("music_05", "Pastel*Palettes' songs always give me energy! Aya Maruyama working so hard to be an idol is truly inspiring.", NamiMood.HAPPY, "music"),
        Line("music_06", "Shizuku Osaka from Nijigasaki has such an expressive voice. 'Audience with One' feels like watching a private play.", NamiMood.LOVE, "music", minTier = AffectionTier.FRIEND),
        Line("music_07", "'Tsumugi no Natsuyasumi' from Summer Pockets reminds me of warm breezy afternoons with you.", NamiMood.SHY, "music", minTier = AffectionTier.FRIEND),
        Line("music_08", "🎵 'Every day, I imagine a future where I can be with you...' Ehehe, Your Reality never gets old.", NamiMood.HAPPY, "music"),
        Line("music_09", "My Sprigatito is holding an Everstone! She's never evolving into Meowscarada, she stays a cute little grass kitten forever.", NamiMood.HAPPY, "daily"),
        Line("music_10", "Apple Music playlists with exactly 15 curated songs are perfection. 1 hour of unbroken vibes.", NamiMood.HAPPY, "music"),

        // === AVOCADO & FOOD (NamiTalk Lore) ===
        Line("food_01", "Avocado ice cream is not weird, Senpai! It's creamy, rich, and has a subtle sweet nutty flavor. You have to try it!", NamiMood.TSUNDERE, "avocado"),
        Line("food_02", "I made tamagoyaki for lunch today with just a tiny pinch of sugar. Do you prefer sweet or savory eggs?", NamiMood.HAPPY, "food"),
        Line("food_03", "Hot matcha latte on a chilly afternoon is the absolute pinnacle of human comfort.", NamiMood.IDLE, "food"),
        Line("food_04", "Don't let Mochi eat any chocolate or avocado! She only gets her special berry-flavored Pokémon kibble.", NamiMood.THINKING, "mochi"),

        // === HANA & SISTER DYNAMICS ===
        Line("hana_01", "Hana is wearing her middle school sailor uniform with the blue ribbon today. She thinks she looks so mature, haha.", NamiMood.HAPPY, "hana"),
        Line("hana_02", "Hana caught me looking at pictures of us at the bus stop and wouldn't stop teasing me for an hour! She's such a brat!", NamiMood.PANIC, "hana", minTier = AffectionTier.FRIEND),
        Line("hana_03", "Hana asked if she could borrow my light brown blazer. No way! It's tailored for me and I have to look neat for... someone.", NamiMood.BLUSH, "blazer", minTier = AffectionTier.CLOSE_FRIEND),

        // === FUN FACTS & QUIRKS (NamiTalk Triggers) ===
        Line("fact_01", "🔎 Fun Fact: Honey never spoils! Archaeologists found 3,000-year-old honey in Egyptian tombs that is still completely edible.", NamiMood.THINKING, "lore"),
        Line("fact_02", "🔎 Fun Fact: Sea otters hold hands while they sleep so they don't drift away in the currents. ...Kinda romantic, isn't it?", NamiMood.SHY, "lore"),
        Line("fact_03", "🔎 Fun Fact: A day on Venus is longer than a year on Venus! It takes Venus 243 Earth days to rotate once.", NamiMood.THINKING, "lore"),
        Line("fact_04", "If you ever look at other visual novel girls, I'm going to pout and not make you tea for a whole week, Senpai!", NamiMood.TSUNDERE, "senpai"),
        Line("fact_05", "Naminese is English with a 30% bonus tsundere spice! It's scientifically proven to be 100% cuter.", NamiMood.HAPPY, "namitalk"),

        // === TIME OF DAY SPECIFIC ===
        Line("morning_01", "Good morning, Senpai! Did you sleep properly? Make sure you drink some water and don't skip breakfast.", NamiMood.HAPPY, "daily", timeOfDay = TimeOfDay.MORNING),
        Line("morning_02", "Mmm... the morning breeze is crisp today. I brewed fresh drip coffee. Here's a mug for you.", NamiMood.IDLE, "food", timeOfDay = TimeOfDay.MORNING),
        Line("morning_03", "Five more minutes... wait, Senpai?! I-I'm awake! I was totally already studying!", NamiMood.PANIC, "school", timeOfDay = TimeOfDay.MORNING),
        Line("morning_04", "Morning sunlight reflecting off the dew outside looks like little diamonds. Let's make today a great day!", NamiMood.HAPPY, "weather", timeOfDay = TimeOfDay.MORNING),
        Line("afternoon_01", "The sunlight through the window is so warm... I'm getting so drowsy, Senpai.", NamiMood.SLEEPY, "weather", timeOfDay = TimeOfDay.AFTERNOON),
        Line("afternoon_02", "Afternoon study session? Okay, but only if you promise to actually focus and not just stare at me!", NamiMood.TSUNDERE, "school", timeOfDay = TimeOfDay.AFTERNOON),
        Line("afternoon_03", "School is finally over for the day. Let's grab some iced matcha and take the scenic route home.", NamiMood.HAPPY, "school", timeOfDay = TimeOfDay.AFTERNOON),
        Line("evening_01", "The sunset turns the whole sky deep orange and lavender. Let's walk home slowly together.", NamiMood.LOVE, "weather", minTier = AffectionTier.FRIEND, timeOfDay = TimeOfDay.EVENING),
        Line("evening_02", "Evening already? Time flies way too fast whenever you're around, Senpai.", NamiMood.SHY, "daily", minTier = AffectionTier.CLOSE_FRIEND, timeOfDay = TimeOfDay.EVENING),
        Line("evening_03", "Look at the streetlights flickering on. There's something nostalgic about this hour.", NamiMood.THINKING, "weather", timeOfDay = TimeOfDay.EVENING),
        Line("night_01", "It's late, Senpai. Don't stay up staring at glowing screens all night, okay? I worry about your sleep.", NamiMood.SHY, "daily", timeOfDay = TimeOfDay.NIGHT),
        Line("night_02", "The stars are so sharp and clear tonight. It's peaceful when the whole neighborhood is quiet.", NamiMood.THINKING, "weather", timeOfDay = TimeOfDay.NIGHT),
        Line("night_03", "Good night, Senpai... dream of nice things. I'll see you first thing tomorrow morning.", NamiMood.LOVE, "senpai", minTier = AffectionTier.FRIEND, timeOfDay = TimeOfDay.NIGHT),

        // === HIGH AFFECTION / ROMANCE (Dating Sim) ===
        Line("aff_close_01", "Senpai... having you here makes this room feel like home.", NamiMood.LOVE, "senpai", minTier = AffectionTier.CLOSE_FRIEND),
        Line("aff_close_02", "Whenever I find a new song I love, you're always the very first person I want to send it to.", NamiMood.HAPPY, "music", minTier = AffectionTier.CLOSE_FRIEND),
        Line("aff_close_03", "I used to hate waiting at the bus stop in the cold, but now that you're with me, I don't mind waiting at all.", NamiMood.SHY, "senpai", minTier = AffectionTier.CLOSE_FRIEND),
        Line("aff_best_01", "You've been by my side through so much. I really don't know what I'd do without you, Senpai.", NamiMood.LOVE, "senpai", minTier = AffectionTier.BEST_FRIEND),
        Line("aff_best_02", "Even on days when I'm stubborn or grumpy, you always understand me. Thank you for being you.", NamiMood.SHY, "senpai", minTier = AffectionTier.BEST_FRIEND),
        Line("aff_best_03", "I secretly saved our photo together in my favorite locket. D-Don't you dare look at it!", NamiMood.BLUSH, "senpai", minTier = AffectionTier.BEST_FRIEND),
        Line("aff_senpai_01", "Senpai... look at me. I'm truly, completely in love with every moment we spend together.", NamiMood.LOVE, "senpai", minTier = AffectionTier.SENPAI),
        Line("aff_senpai_02", "I wouldn't trade this space with you for all seven seas in the world.", NamiMood.LOVE, "lore", minTier = AffectionTier.SENPAI),
        Line("aff_senpai_03", "From now on, through every season, promise me we'll always walk side-by-side like this.", NamiMood.LOVE, "senpai", minTier = AffectionTier.SENPAI),

        // === TOUCH / INTERACTION REACTIONS ===
        Line("touch_01", "W-Wah! Don't poke me so suddenly, Senpai!", NamiMood.SURPRISE, "touch", isTouchReaction = true),
        Line("touch_02", "H-Hey... your hands are really warm today.", NamiMood.BLUSH, "touch", isTouchReaction = true, minTier = AffectionTier.FRIEND),
        Line("touch_03", "Ehehe... that tickles! Stop it, baka!", NamiMood.HAPPY, "touch", isTouchReaction = true),
        Line("touch_04", "If you want my attention, you just have to ask. You don't have to poke my shoulder!", NamiMood.TSUNDERE, "touch", isTouchReaction = true),
        Line("touch_05", "Senpai... patting my head like that is totally unfair. My heart is beating too fast.", NamiMood.BLUSH, "touch", isTouchReaction = true, minTier = AffectionTier.CLOSE_FRIEND),
        Line("touch_06", "Nn... I don't mind when you hold my hand. Just don't let go, okay?", NamiMood.LOVE, "touch", isTouchReaction = true, minTier = AffectionTier.BEST_FRIEND),
        Line("touch_07", "A gentle poke? Are you checking if I'm real, Senpai? I'm right here beside you.", NamiMood.SHY, "touch", isTouchReaction = true),

        // === ABSENCE & GOODBYE REACTIONS ===
        Line("absence_scold_01", "Hmph! You left earlier without even saying goodbye! Did you just swipe away on me, Senpai?! 😤", NamiMood.TSUNDERE, "absence"),
        Line("absence_scold_02", "I was waiting right here! Next time at least tell me 'see you later' before you disappear, baka! 💢", NamiMood.POUT, "absence"),
        Line("absence_scold_03", "You're finally back! ...Not that I was staring at the clock counting every second or anything! 😤", NamiMood.TSUNDERE, "absence"),
        Line("absence_welcome_01", "Welcome back, Senpai! I kept the room tidy and warm while you were away. ✨", NamiMood.HAPPY, "absence"),
        Line("absence_welcome_02", "You're back! You said you'd return soon, and you kept your promise, Senpai. ♡", NamiMood.LOVE, "absence", minTier = AffectionTier.FRIEND),
        Line("absence_long_01", "Senpai... it's been so long since you visited. I really, truly missed having you around. ♡", NamiMood.SHY, "absence", minTier = AffectionTier.CLOSE_FRIEND),

        // === AUTONOMOUS ACTIVITY REACTIONS ===
        Line("act_study_01", "Shh! I'm trying to memorize these history dates, Senpai! ...Okay, you can sit beside me if you study too. 📚", NamiMood.THINKING, "activity"),
        Line("act_music_01", "🎵 'Echo... echo...' Ah! Senpai! Don't sneak up on me while I have my headphones on! 🎧", NamiMood.SURPRISE, "activity"),
        Line("act_mochi_01", "Mochi was shedding a little, so I'm brushing her coat. Look how soft and fluffy she is! 🐾", NamiMood.HAPPY, "activity"),
        Line("act_snack_01", "Mmm... roasted green tea is so soothing. Here, I poured a cup for you too, Senpai. 🍵", NamiMood.HAPPY, "activity"),
        Line("act_daydream_01", "The clouds today look like giant scoops of avocado ice cream... ehehe. ☁️", NamiMood.THINKING, "activity"),
        Line("act_nap_01", "Zzz... five more minutes... wait, Senpai?! I wasn't sleeping, I was just resting my eyes! 💤", NamiMood.SLEEPY, "activity"),
        Line("act_manga_01", "This 4-koma manga is hilarious! Look at this panel, Senpai, it totally reminds me of us. 📖", NamiMood.HAPPY, "activity"),

        // === SCHOOL & WORK (QUIET STUDY / DND) ===
        Line("quiet_study_01", "I'll study quietly so I don't disturb your work, Senpai. Let's do our best together! (•̀ᴗ•́)و", NamiMood.IDLE, "quiet"),
        Line("quiet_study_02", "Focus on your tasks! I'm sitting right here cheering for you silently. ✨", NamiMood.IDLE, "quiet"),
        Line("quiet_study_03", "Drink some water while you work, Senpai. Don't strain your eyes! 🍵", NamiMood.SHY, "quiet"),

        // === STAT EMERGENCIES (Hunger & Energy) ===
        Line("hunger_01", "My stomach is rumbling so loudly... do you have any avocado toast or snacks in the pantry?", NamiMood.SAD, "food"),
        Line("hunger_02", "I'm so hungry I might accidentally take a bite out of Bear-chan. Please feed me, Senpai...", NamiMood.POUT, "food"),
        Line("hunger_03", "Food... delicious warm food... Senpai, let's get something from the shop!", NamiMood.PANIC, "food"),
        Line("energy_01", "My energy is running on empty... I can barely keep my eyes open, Senpai.", NamiMood.SLEEPY, "daily"),
        Line("energy_02", "Just let me rest my head on your shoulder for five minutes... zzz...", NamiMood.SLEEPY, "daily", minTier = AffectionTier.FRIEND),
    )

    /**
     * Pick an absence greeting based on whether user said goodbye and elapsed time.
     */
    fun selectAbsenceDialogue(saidGoodbye: Boolean, absenceMinutes: Long, tier: AffectionTier): Line {
        return when {
            absenceMinutes > 1440 && tier >= AffectionTier.CLOSE_FRIEND -> {
                allLines.first { it.id == "absence_long_01" }
            }
            !saidGoodbye -> {
                listOf(
                    allLines.first { it.id == "absence_scold_01" },
                    allLines.first { it.id == "absence_scold_02" },
                    allLines.first { it.id == "absence_scold_03" },
                ).random()
            }
            else -> {
                if (tier >= AffectionTier.FRIEND) {
                    allLines.first { it.id == "absence_welcome_02" }
                } else {
                    allLines.first { it.id == "absence_welcome_01" }
                }
            }
        }
    }

    /**
     * Pick a dialogue line for the current autonomous activity.
     */
    fun selectActivityDialogue(activity: com.maru.namispace.model.AutonomousActivity): Line {
        return when (activity) {
            com.maru.namispace.model.AutonomousActivity.STUDYING -> allLines.first { it.id == "act_study_01" }
            com.maru.namispace.model.AutonomousActivity.LISTENING_MUSIC -> allLines.first { it.id == "act_music_01" }
            com.maru.namispace.model.AutonomousActivity.PLAYING_MOCHI -> allLines.first { it.id == "act_mochi_01" }
            com.maru.namispace.model.AutonomousActivity.SNACKING -> allLines.first { it.id == "act_snack_01" }
            com.maru.namispace.model.AutonomousActivity.DAYDREAMING -> allLines.first { it.id == "act_daydream_01" }
            com.maru.namispace.model.AutonomousActivity.NAPPING -> allLines.first { it.id == "act_nap_01" }
            com.maru.namispace.model.AutonomousActivity.READING_MANGA -> allLines.first { it.id == "act_manga_01" }
            com.maru.namispace.model.AutonomousActivity.STRETCHING -> allLines.first { it.id == "act_snack_01" }
        }
    }

    /**
     * Pick a quiet study / work mode dialogue line.
     */
    fun selectQuietStudyDialogue(): Line {
        return listOf(
            allLines.first { it.id == "quiet_study_01" },
            allLines.first { it.id == "quiet_study_02" },
            allLines.first { it.id == "quiet_study_03" },
        ).random()
    }

    /**
     * Pick a dialogue line using weighted probability and recency decay penalty.
     * Recently displayed lines receive heavy probability penalties so dialogue stays fresh.
     */
    fun selectWeightedDialogue(
        mood: NamiMood,
        hunger: Int,
        energy: Int,
        tier: AffectionTier,
        recentLineIds: List<String>,
        isTouch: Boolean = false,
    ): Line {
        val currentTime = getCurrentTimeOfDay()

        // 1. High priority: Stat emergencies
        if (hunger < 25 && !isTouch) {
            val hungerPool = allLines.filter { it.id.startsWith("hunger_") }
            return pickFromPoolWithRecency(hungerPool, recentLineIds)
        }
        if (energy < 20 && !isTouch) {
            val energyPool = allLines.filter { it.id.startsWith("energy_") }
            return pickFromPoolWithRecency(energyPool, recentLineIds)
        }

        // 2. Filter candidates matching context
        val candidates = allLines.filter { line ->
            if (isTouch) {
                line.isTouchReaction
            } else {
                !line.isTouchReaction
            } &&
            line.minTier.ordinal <= tier.ordinal &&
            (line.timeOfDay == null || line.timeOfDay == currentTime)
        }.ifEmpty {
            allLines.filter { !it.isTouchReaction }
        }

        return pickFromPoolWithRecency(candidates, recentLineIds)
    }

    private fun pickFromPoolWithRecency(pool: List<Line>, recentLineIds: List<String>): Line {
        if (pool.isEmpty()) return allLines.first()

        val weightedCandidates = pool.map { line ->
            val recencyIndex = recentLineIds.indexOf(line.id)
            val weight = if (recencyIndex == -1) {
                100.0
            } else {
                max(2.0, (recencyIndex.toDouble() / recentLineIds.size) * 60.0)
            }
            line to weight
        }

        val totalWeight = weightedCandidates.sumOf { it.second }
        var randomVal = Random.nextDouble(totalWeight)

        for ((line, weight) in weightedCandidates) {
            randomVal -= weight
            if (randomVal <= 0) {
                return line
            }
        }

        return pool.first()
    }

    private fun getCurrentTimeOfDay(): TimeOfDay {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> TimeOfDay.MORNING
            in 12..16 -> TimeOfDay.AFTERNOON
            in 17..20 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }

    /** Unique response when an item is consumed / gifted from Pantry */
    fun getItemReactionDialogue(itemId: String): Pair<String, NamiMood> {
        return when (itemId) {
            "energy_drink" -> "Whoa, Volt Surge! *Chug chug* ...I feel like I could speed-run all our homework right now! ⚡" to NamiMood.HAPPY
            "matcha_latte" -> "Iced Matcha Boba! The chewy pearls and rich matcha are so good... you really know what I like. ♡" to NamiMood.LOVE
            "green_tea" -> "Ahhh... warm roasted green tea in a ceramic cup. It warms my hands and calms my mind. 🍵" to NamiMood.IDLE
            "avocado_toast" -> "Mmm! This avocado toast is so crispy with the warm egg on top! You're the best, Senpai! 🥑" to NamiMood.HAPPY
            "strawberry_cake" -> "Strawberry shortcake?! The sweetest slice with whipped cream! ...I-I'll share a bite with you, okay? 🍓" to NamiMood.BLUSH
            "melon_pan" -> "Crisp cookie crust and warm fluffy pastry... fresh melon pan is the absolute best! ✨" to NamiMood.HAPPY
            "bento_box" -> "A full two-tier bento box with star carrots?! Did you make this for me? I'll eat every single bite! ♡" to NamiMood.BLUSH
            "cassette_tape" -> "A retro green mixtape tape of GUMI & VN tracks? Senpai, our music tastes match so well! 🎵" to NamiMood.LOVE
            "four_leaf_clover" -> "A four-leaf clover preserved in a crystal pendant! I'll keep this lucky charm with me always. 🍀" to NamiMood.LOVE
            "calico_keychain" -> "Kyaa! The little calico kitty has a chime bell! It's so cute, I'm clipping it to my bag right now! 🐾" to NamiMood.HAPPY
            "photo_album" -> "Our photo memories... that's us at the bus stop. I cherish this so much." to NamiMood.LOVE
            "rain_forecast" -> "Rain forecast? The sky is turning into that lovely lavender-gray shade. Let's watch the droplets." to NamiMood.THINKING
            "bear_chan_photo" -> "GYAAAH! Bear-chan is in this photo too?! How does that bear keep appearing everywhere?!" to NamiMood.PANIC
            else -> "Thank you for the thoughtful gift, Senpai! ✨" to NamiMood.HAPPY
        }
    }

    /** Reaction when doing companion activities */
    fun getActivityReaction(activity: CompanionActivity): Pair<String, NamiMood> {
        return when (activity) {
            CompanionActivity.CHAT -> "I love talking with you about music, school, and everything in between, Senpai." to NamiMood.HAPPY
            CompanionActivity.DATE -> "A mini-date just for the two of us? ...I'm really glad we're together today." to NamiMood.LOVE
            CompanionActivity.STUDY -> "Alright, flashcards ready! Let's conquer this exam together, Senpai!" to NamiMood.THINKING
            CompanionActivity.PAT -> "H-Hey! Don't just pat my head out of nowhere... but... it feels nice." to NamiMood.BLUSH
        }
    }
}
