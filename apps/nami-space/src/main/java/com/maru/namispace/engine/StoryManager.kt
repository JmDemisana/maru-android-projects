package com.maru.namispace.engine

import com.maru.namispace.model.NamiMood
import com.maru.namispace.model.StoryChapter
import com.maru.namispace.model.StoryChoice
import com.maru.namispace.model.StoryNode

/**
 * Manages story unlock progression and chapter navigation with rich visual novel chapters.
 */
class StoryManager {

    private val chapters = mutableListOf<StoryChapter>()

    init {
        loadChapters()
    }

    fun getChapters(): List<StoryChapter> = chapters.toList()

    fun getChapter(id: String): StoryChapter? = chapters.find { it.id == id }

    fun canUnlock(chapter: StoryChapter, affectionLevel: Int, topicsDiscussed: Set<String>): Boolean {
        if (affectionLevel < chapter.requiredAffection) return false
        return chapter.requiredTopics.all { it in topicsDiscussed }
    }

    fun getUnlockedChapters(affectionLevel: Int, topicsDiscussed: Set<String>): List<StoryChapter> {
        return chapters.filter { canUnlock(it, affectionLevel, topicsDiscussed) }
    }

    private fun loadChapters() {
        chapters.addAll(listOf(
            // === CHAPTER 1: THE BUS STOP ENCOUNTER ===
            StoryChapter(
                id = "ch1",
                title = "The Bus Stop in the Rain",
                description = "A quiet afternoon at the bus stop where two paths first crossed.",
                requiredAffection = 0,
                nodes = listOf(
                    StoryNode(
                        id = "ch1_1",
                        speaker = "narrator",
                        text = "A gentle drizzle taps against the glass shelter of the bus stop. The asphalt glistens under soft gray afternoon light.",
                        mood = NamiMood.IDLE,
                        nextNodeId = "ch1_2",
                    ),
                    StoryNode(
                        id = "ch1_2",
                        speaker = "narrator",
                        text = "Standing under the awning is a girl in a tailored light brown blazer, her chestnut bob tinged with subtle lavender tips swaying in the breeze.",
                        mood = NamiMood.IDLE,
                        nextNodeId = "ch1_3",
                    ),
                    StoryNode(
                        id = "ch1_3",
                        speaker = "nami",
                        text = "...You're staring. Is my ribbon crooked, or did you miss your bus too, Senpai?",
                        mood = NamiMood.TSUNDERE,
                        choices = listOf(
                            StoryChoice("Your green ribbon looks really nice.", "ch1_ribbon", affectionBonus = 3),
                            StoryChoice("Nanami means Seven Seas and Shiro means White, right?", "ch1_name", affectionBonus = 5),
                            StoryChoice("Just waiting for the rain to slow down.", "ch1_rain", affectionBonus = 2),
                        ),
                    ),
                    StoryNode(
                        id = "ch1_ribbon",
                        speaker = "nami",
                        text = "W-What?! Don't just compliment people out of nowhere, baka! ...T-Thanks, though. Hana ironed it for me this morning.",
                        mood = NamiMood.BLUSH,
                        nextNodeId = "ch1_shared",
                    ),
                    StoryNode(
                        id = "ch1_name",
                        speaker = "nami",
                        text = "Eh?! You actually know that?! Most people just assume I love swimming. The truth is, I can't even swim in the shallow end!",
                        mood = NamiMood.SURPRISE,
                        nextNodeId = "ch1_shared",
                    ),
                    StoryNode(
                        id = "ch1_rain",
                        speaker = "nami",
                        text = "Yeah... the rain has a calming rhythm to it. Kind of like the opening piano chords of a quiet ballad.",
                        mood = NamiMood.THINKING,
                        nextNodeId = "ch1_shared",
                    ),
                    StoryNode(
                        id = "ch1_shared",
                        speaker = "nami",
                        text = "I brought my green umbrella. If your bus is delayed, we can walk together... but only because we're heading in the same direction!",
                        mood = NamiMood.HAPPY,
                        nextNodeId = "ch1_end",
                    ),
                    StoryNode(
                        id = "ch1_end",
                        speaker = "narrator",
                        text = "Under the shared green canopy, the walk home felt much shorter than usual. A new bond was quietly formed.",
                        mood = NamiMood.LOVE,
                    ),
                ),
            ),

            // === CHAPTER 2: AVOCADO TOAST & SISTERLY GOSSIP ===
            StoryChapter(
                id = "ch2",
                title = "Avocado Toast & Sisterly Gossip",
                description = "A lunchtime revelation involving secret recipes and a nosy middle school sister.",
                requiredAffection = 5,
                nodes = listOf(
                    StoryNode(
                        id = "ch2_1",
                        speaker = "narrator",
                        text = "Lunchtime. The classroom empties out as students head for the cafeteria, leaving behind a tranquil warm breeze through the window.",
                        mood = NamiMood.IDLE,
                        nextNodeId = "ch2_2",
                    ),
                    StoryNode(
                        id = "ch2_2",
                        speaker = "nami",
                        text = "Senpai! Look what I made this morning. Sourdough avocado toast with sesame seasoning and Japanese sweet tamagoyaki!",
                        mood = NamiMood.HAPPY,
                        nextNodeId = "ch2_3",
                    ),
                    StoryNode(
                        id = "ch2_3",
                        speaker = "nami",
                        text = "D-Don't look at me like that! It's not like I woke up 30 minutes early just to pack enough for two people... okay, maybe I did.",
                        mood = NamiMood.TSUNDERE,
                        choices = listOf(
                            StoryChoice("This is delicious! You're surprisingly microwavable, Nami.", "ch2_micro", affectionBonus = 4),
                            StoryChoice("Did Hana try to steal some before you left?", "ch2_hana", affectionBonus = 3),
                        ),
                    ),
                    StoryNode(
                        id = "ch2_micro",
                        speaker = "nami",
                        text = "Microwavable?! ...W-Wait, is that your way of calling me cute?! Uuu, you can't just drop compliments while chewing toast!",
                        mood = NamiMood.BLUSH,
                        nextNodeId = "ch2_phone",
                    ),
                    StoryNode(
                        id = "ch2_hana",
                        speaker = "nami",
                        text = "She totally did! She barged into the kitchen in her sailor uniform and asked: 'Is that for your Senpai?' She's so cheeky!",
                        mood = NamiMood.PANIC,
                        nextNodeId = "ch2_phone",
                    ),
                    StoryNode(
                        id = "ch2_phone",
                        speaker = "narrator",
                        text = "Nanami's phone buzzes on the desk. A text from Hana: 'Did Senpai say it was tasty yet? (´∀｀*)'",
                        mood = NamiMood.IDLE,
                        nextNodeId = "ch2_end",
                    ),
                    StoryNode(
                        id = "ch2_end",
                        speaker = "nami",
                        text = "Haaah... I'm never leaving my phone unlocked around her again. But... I'm glad you liked the food, Senpai.",
                        mood = NamiMood.HAPPY,
                    ),
                ),
            ),

            // === CHAPTER 3: VOCALOID PLAYLISTS & RAINY AFTERNOONS ===
            StoryChapter(
                id = "ch3",
                title = "Vocaloid Playlists & Rainy Melody",
                description = "Sharing earbuds in the library corner to the sound of GUMI and classical strings.",
                requiredAffection = 15,
                requiredTopics = listOf("gumi"),
                nodes = listOf(
                    StoryNode(
                        id = "ch3_1",
                        speaker = "narrator",
                        text = "Rain streams down the library windows. In the quiet corner between bookshelves, Nanami untangles a pair of wired earphones.",
                        mood = NamiMood.IDLE,
                        nextNodeId = "ch3_2",
                    ),
                    StoryNode(
                        id = "ch3_2",
                        speaker = "nami",
                        text = "Here, take the left earbud. I made a special 15-song Apple Music playlist... exactly one hour of Vocaloid and VN soundtracks.",
                        mood = NamiMood.SHY,
                        nextNodeId = "ch3_3",
                    ),
                    StoryNode(
                        id = "ch3_3",
                        speaker = "nami",
                        text = "The first track is GUMI's 'Coward Montblanc', followed by 'Tsumugi no Natsuyasumi' from Summer Pockets. What do you think?",
                        mood = NamiMood.HAPPY,
                        choices = listOf(
                            StoryChoice("The melody is so peaceful... perfectly matches the rain.", "ch3_peace", affectionBonus = 4),
                            StoryChoice("🎵 'Every day, I imagine a future where I can be with you...'", "ch3_lyric", affectionBonus = 5),
                        ),
                    ),
                    StoryNode(
                        id = "ch3_peace",
                        speaker = "nami",
                        text = "Right? When the acoustic guitar kicks in, it feels like the whole world slows down just for us.",
                        mood = NamiMood.LOVE,
                        nextNodeId = "ch3_close",
                    ),
                    StoryNode(
                        id = "ch3_lyric",
                        speaker = "nami",
                        text = "Ehehe! Continuing Doki Doki lyrics? 'In my hand is a pen that will write a poem of me and you.' You remembered!",
                        mood = NamiMood.HAPPY,
                        nextNodeId = "ch3_close",
                    ),
                    StoryNode(
                        id = "ch3_close",
                        speaker = "narrator",
                        text = "Nanami leans in slightly closer so the earphone cable doesn't pull. Her hair carries a light vanilla and rain scent.",
                        mood = NamiMood.SHY,
                        nextNodeId = "ch3_end",
                    ),
                    StoryNode(
                        id = "ch3_end",
                        speaker = "nami",
                        text = "Senpai... moments like this make me wish the rain would never stop.",
                        mood = NamiMood.LOVE,
                    ),
                ),
            ),

            // === CHAPTER 4: MOCHI'S BIG ADVENTURE & BEAR-CHAN ===
            StoryChapter(
                id = "ch4",
                title = "Mochi's Big Adventure & Bear-chan",
                description = "An afternoon park walk with Mochi the Eevee and a mysterious photobombing bear.",
                requiredAffection = 30,
                requiredTopics = listOf("mochi"),
                nodes = listOf(
                    StoryNode(
                        id = "ch4_1",
                        speaker = "narrator",
                        text = "Cherry blossom petals drift across the neighborhood park. Mochi trotting proudly on her leash, tail wagging like a windmill.",
                        mood = NamiMood.HAPPY,
                        nextNodeId = "ch4_2",
                    ),
                    StoryNode(
                        id = "ch4_2",
                        speaker = "nami",
                        text = "Look at Mochi! She saw a calico cat sitting on the park fence and tried to make friends. She really is the sweetest Eevee.",
                        mood = NamiMood.HAPPY,
                        nextNodeId = "ch4_3",
                    ),
                    StoryNode(
                        id = "ch4_3",
                        speaker = "nami",
                        text = "Wait... Senpai, look at that bench over there! Is that... Bear-chan sitting behind the bushes?!",
                        mood = NamiMood.PANIC,
                        choices = listOf(
                            StoryChoice("Quick! Take a photo before Bear-chan disappears!", "ch4_photo", affectionBonus = 4),
                            StoryChoice("Don't worry, I'll protect you and Mochi from the mysterious bear.", "ch4_protect", affectionBonus = 5),
                        ),
                    ),
                    StoryNode(
                        id = "ch4_photo",
                        speaker = "nami",
                        text = "Snap! Got it! Haha, look, Bear-chan looks so goofy peeking out from the cherry leaves!",
                        mood = NamiMood.HAPPY,
                        nextNodeId = "ch4_wrap",
                    ),
                    StoryNode(
                        id = "ch4_protect",
                        speaker = "nami",
                        text = "Pfft... protect me from a plush toy? You really are a hopeless romantic, Senpai... but thank you.",
                        mood = NamiMood.BLUSH,
                        nextNodeId = "ch4_wrap",
                    ),
                    StoryNode(
                        id = "ch4_wrap",
                        speaker = "nami",
                        text = "Mochi is curled up on my lap now, fast asleep. Her fur smells like sweet roasted vanilla.",
                        mood = NamiMood.LOVE,
                        nextNodeId = "ch4_end",
                    ),
                    StoryNode(
                        id = "ch4_end",
                        speaker = "narrator",
                        text = "The afternoon sun dipped below the horizon, painting the park in rich gold and violet tones.",
                        mood = NamiMood.LOVE,
                    ),
                ),
            ),

            // === CHAPTER 5: SUNSET PROMISE & THE SEVEN SEAS ===
            StoryChapter(
                id = "ch5",
                title = "Sunset Promise & The Seven Seas",
                description = "On the school rooftop overlooking the evening horizon, feelings are laid bare.",
                requiredAffection = 50,
                requiredTopics = listOf("senpai"),
                nodes = listOf(
                    StoryNode(
                        id = "ch5_1",
                        speaker = "narrator",
                        text = "The school rooftop at sunset. The horizon stretches endlessly, where the orange sky melts into the distant blue ocean.",
                        mood = NamiMood.IDLE,
                        nextNodeId = "ch5_2",
                    ),
                    StoryNode(
                        id = "ch5_2",
                        speaker = "nami",
                        text = "You know, Senpai... when I was little, I used to wonder why I was named Nanami. Seven Seas felt too vast and intimidating.",
                        mood = NamiMood.THINKING,
                        nextNodeId = "ch5_3",
                    ),
                    StoryNode(
                        id = "ch5_3",
                        speaker = "nami",
                        text = "I was scared of getting lost in all that endless water. But since meeting you... this room, this space, feels like my true safe harbor.",
                        mood = NamiMood.SHY,
                        choices = listOf(
                            StoryChoice("You'll never get lost as long as we're together.", "ch5_together", affectionBonus = 10),
                            StoryChoice("I love every single moment I spend with you, Nami.", "ch5_love", affectionBonus = 10),
                        ),
                    ),
                    StoryNode(
                        id = "ch5_together",
                        speaker = "nami",
                        text = "Senpai... don't say such cheesy things with a straight face! ...My heart is beating so loud you can probably hear it.",
                        mood = NamiMood.BLUSH,
                        nextNodeId = "ch5_end",
                    ),
                    StoryNode(
                        id = "ch5_love",
                        speaker = "nami",
                        text = "Me too. More than all the seven seas in the world. Promise me you'll stay by my side through every season to come.",
                        mood = NamiMood.LOVE,
                        nextNodeId = "ch5_end",
                    ),
                    StoryNode(
                        id = "ch5_end",
                        speaker = "narrator",
                        text = "As the first evening stars appeared in the twilight sky, two hands found each other against the rooftop railing. A promise sealed for all seven seas.",
                        mood = NamiMood.LOVE,
                    ),
                ),
            ),
        ))
    }
}
