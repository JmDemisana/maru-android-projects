package com.maru.namispace.model

enum class AutonomousActivity(
    val id: String,
    val label: String,
    val icon: String,
    val defaultMood: NamiMood,
) {
    STUDYING("studying", "Studying Literature & History", "📚", NamiMood.THINKING),
    LISTENING_MUSIC("music", "Listening to GUMI with Headphones", "🎧", NamiMood.HAPPY),
    PLAYING_MOCHI("mochi", "Brushing Mochi the Eevee", "🐾", NamiMood.HAPPY),
    SNACKING("snacking", "Sipping Roasted Green Tea", "🍵", NamiMood.IDLE),
    DAYDREAMING("daydreaming", "Daydreaming by the Window", "☁️", NamiMood.THINKING),
    NAPPING("napping", "Taking a Peaceful Nap", "💤", NamiMood.SLEEPY),
    READING_MANGA("manga", "Reading Comedy Manga", "📖", NamiMood.HAPPY),
    STRETCHING("stretching", "Stretching Her Arms", "✨", NamiMood.IDLE);

    companion object {
        fun pickForState(hunger: Int, energy: Int, mood: NamiMood): AutonomousActivity {
            return when {
                energy < 30 -> NAPPING
                hunger < 40 -> SNACKING
                mood == NamiMood.HAPPY -> listOf(LISTENING_MUSIC, PLAYING_MOCHI, READING_MANGA).random()
                mood == NamiMood.THINKING -> listOf(STUDYING, DAYDREAMING).random()
                else -> entries.random()
            }
        }
    }
}

enum class LocationMode(
    val id: String,
    val label: String,
    val icon: String,
    val description: String,
) {
    HOME("home", "Home / Room", "🏠", "Cozy companion space"),
    SCHOOL_WORK("school_work", "School / Work (Quiet)", "🏫", "Quiet study & focus timer"),
    OUTING("outing", "Outing / Walk", "🚶‍♀️", "GPS walking & souvenir encounters"),
}

data class OutingEncounter(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val coinsReward: Int = 2,
    val affectionBonus: Int = 3,
    val souvenir: String? = null,
)

object OutingCatalog {
    val encounters = listOf(
        OutingEncounter(
            id = "clover",
            title = "Four-Leaf Clover in the Grass",
            description = "You and Nanami spotted a lucky 4-leaf clover hiding near the sidewalk.",
            icon = "🍀",
            coinsReward = 3,
            affectionBonus = 4,
            souvenir = "Pressed Four-Leaf Clover",
        ),
        OutingEncounter(
            id = "calico_cat",
            title = "Friendly Calico Cat",
            description = "A curious calico cat strolled up to rub against your sneakers.",
            icon = "🐱",
            coinsReward = 2,
            affectionBonus = 3,
            souvenir = "Cat Whisker Charm",
        ),
        OutingEncounter(
            id = "crepe_stand",
            title = "Street Crepe Stand",
            description = "The sweet aroma of strawberry matcha crepes drifted from a corner cart.",
            icon = "🍓",
            coinsReward = 4,
            affectionBonus = 5,
            souvenir = "Sweet Strawberry Wrapper",
        ),
        OutingEncounter(
            id = "record_shop",
            title = "Retro Vinyl Record Shop",
            description = "Nanami peeked inside a vintage shop playing nostalgic Vocaloid synth tunes.",
            icon = "🎵",
            coinsReward = 5,
            affectionBonus = 5,
            souvenir = "Vintage Music Badge",
        ),
        OutingEncounter(
            id = "cherry_blossom",
            title = "Cherry Blossom Breeze",
            description = "A sudden gust of wind scattered soft pink petals across the path.",
            icon = "🌸",
            coinsReward = 2,
            affectionBonus = 4,
            souvenir = "Pressed Cherry Blossom Petal",
        ),
    )
}
