package com.maru.namispace.model

import androidx.annotation.DrawableRes
import com.maru.namispace.R

enum class NamiMood(
    val label: String,
    @param:DrawableRes val sprite: Int,
    val greeting: String,
    val color: Long,
) {
    IDLE(
        label = "Calm",
        sprite = R.drawable.nami_idle,
        greeting = "Welcome back, Senpai.",
        color = 0xFF6EE7A0,
    ),
    HAPPY(
        label = "Happy",
        sprite = R.drawable.nami_confident,
        greeting = "Nn... it's nice to see you today!",
        color = 0xFFFFD54F,
    ),
    TSUNDERE(
        label = "Hmph",
        sprite = R.drawable.nami_tsundere,
        greeting = "I-It's not like I was waiting for you or anything...",
        color = 0xFFFF8A9E,
    ),
    SLEEPY(
        label = "Sleepy",
        sprite = R.drawable.nami_tired,
        greeting = "Mmm... five more minutes...",
        color = 0xFF90CAF9,
    ),
    SURPRISE(
        label = "Eh?!",
        sprite = R.drawable.nami_surprised,
        greeting = "Eh?! You startled me!",
        color = 0xFFFF80AB,
    ),
    SHY(
        label = "Shy",
        sprite = R.drawable.nami_shy,
        greeting = "U-um... h-hello...",
        color = 0xFFFFAB91,
    ),
    SAD(
        label = "Sad",
        sprite = R.drawable.nami_crying,
        greeting = "...",
        color = 0xFF90A4AE,
    ),
    ANGRY(
        label = "Angry",
        sprite = R.drawable.nami_angry,
        greeting = "I'm not angry! ...Maybe a little.",
        color = 0xFFEF5350,
    ),
    LOVE(
        label = "Love",
        sprite = R.drawable.nami_love,
        greeting = "Senpai... you mean a lot to me.",
        color = 0xFFE91E63,
    ),
    PANIC(
        label = "Panic",
        sprite = R.drawable.nami_panic,
        greeting = "W-wait wait wait this is too much!!",
        color = 0xFFFF7043,
    ),
    THINKING(
        label = "Thinking",
        sprite = R.drawable.nami_thinking,
        greeting = "Hmm... let me think about that.",
        color = 0xFFAB47BC,
    ),
    BLUSH(
        label = "Blush",
        sprite = R.drawable.nami_blush,
        greeting = "I-It's warm today, isn't it...",
        color = 0xFFEC407A,
    ),
    POUT(
        label = "Pout",
        sprite = R.drawable.nami_pout,
        greeting = "Hmph. I'm fine.",
        color = 0xFF7E57C2,
    ),
    POSE(
        label = "Pose",
        sprite = R.drawable.nami_pose1,
        greeting = "How do I look?",
        color = 0xFF26A69A,
    ),
    POSE2(
        label = "Pose",
        sprite = R.drawable.nami_pose2,
        greeting = "Senpai, are you looking?",
        color = 0xFF5C6BC0,
    );

    companion object {
        private val friendlyMoods = listOf(HAPPY, SHY, LOVE, THINKING)
        private val neutralMoods = listOf(IDLE, POSE, POSE2, BLUSH)
        private val negativeMoods = listOf(TSUNDERE, SAD, ANGRY, POUT)
        private val rareMoods = listOf(PANIC, SLEEPY, SURPRISE)

        fun randomFriendly(): NamiMood = friendlyMoods.random()
        fun randomNeutral(): NamiMood = neutralMoods.random()
        fun randomNegative(): NamiMood = negativeMoods.random()
        fun randomRare(): NamiMood = rareMoods.random()
    }
}
