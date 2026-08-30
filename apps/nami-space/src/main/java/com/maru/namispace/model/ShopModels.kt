package com.maru.namispace.model

import com.maru.namispace.R

data class CurrencyState(
    val coins: Int = 0,
    val totalEarned: Int = 0,
    val totalSpent: Int = 0,
) {
    val canAfford: (Int) -> Boolean = { coins >= it }
}

enum class ShopCategory(val icon: String, val label: String) {
    FOOD("🍽", "Food & Drinks"),
    GIFT("🎁", "Gifts"),
    MOOD("✨", "Mood"),
    BACKGROUND("🎨", "Backgrounds"),
}

data class ShopItem(
    val id: String,
    val name: String,
    val description: String,
    val price: Int,
    val category: ShopCategory,
    val hungerRestore: Int = 0,
    val energyRestore: Int = 0,
    val affectionBonus: Int = 0,
    val moodEffect: NamiMood? = null,
    val isConsumable: Boolean = true,
    val ownedCount: Int = 0,
    val drawableRes: Int? = null,
)

object ShopCatalog {

    val items = listOf(
        // Drinks & Food
        ShopItem(
            id = "energy_drink",
            name = "Volt Surge Energy",
            description = "Neon lime surge! Perfect for late study sessions.",
            price = 6,
            category = ShopCategory.FOOD,
            hungerRestore = 5,
            energyRestore = 45,
            affectionBonus = 2,
            moodEffect = NamiMood.HAPPY,
            drawableRes = R.drawable.item_energy_drink,
        ),
        ShopItem(
            id = "matcha_latte",
            name = "Iced Matcha Boba",
            description = "Sweet matcha with chewy pearls. Nami's favorite green tea treat.",
            price = 8,
            category = ShopCategory.FOOD,
            hungerRestore = 20,
            energyRestore = 25,
            affectionBonus = 3,
            moodEffect = NamiMood.HAPPY,
            drawableRes = R.drawable.item_matcha_latte,
        ),
        ShopItem(
            id = "green_tea",
            name = "Ceramic Green Tea",
            description = "A warm, comforting cup of roasted green tea.",
            price = 4,
            category = ShopCategory.FOOD,
            hungerRestore = 10,
            energyRestore = 20,
            affectionBonus = 2,
            moodEffect = NamiMood.IDLE,
            drawableRes = R.drawable.item_green_tea,
        ),
        ShopItem(
            id = "avocado_toast",
            name = "Avocado Egg Toast",
            description = "Freshly sliced creamy avocado & egg on sourdough.",
            price = 5,
            category = ShopCategory.FOOD,
            hungerRestore = 35,
            energyRestore = 10,
            affectionBonus = 2,
            moodEffect = NamiMood.HAPPY,
            drawableRes = R.drawable.item_avocado_toast,
        ),
        ShopItem(
            id = "strawberry_cake",
            name = "Strawberry Shortcake",
            description = "Fluffy sponge with layers of fresh cream and ripe strawberries.",
            price = 7,
            category = ShopCategory.FOOD,
            hungerRestore = 30,
            energyRestore = 15,
            affectionBonus = 3,
            moodEffect = NamiMood.LOVE,
            drawableRes = R.drawable.item_strawberry_cake,
        ),
        ShopItem(
            id = "melon_pan",
            name = "Golden Melon Pan",
            description = "Sweet cookie crust pastry freshly baked to perfection.",
            price = 4,
            category = ShopCategory.FOOD,
            hungerRestore = 25,
            energyRestore = 10,
            affectionBonus = 2,
            moodEffect = NamiMood.HAPPY,
            drawableRes = R.drawable.item_melon_pan,
        ),
        ShopItem(
            id = "bento_box",
            name = "Deluxe Bento Box",
            description = "Two-tier lunch with tamagoyaki, chicken, and star carrots.",
            price = 12,
            category = ShopCategory.FOOD,
            hungerRestore = 55,
            energyRestore = 20,
            affectionBonus = 4,
            moodEffect = NamiMood.HAPPY,
            drawableRes = R.drawable.item_bento_box,
        ),

        // Gifts & Keepsakes
        ShopItem(
            id = "cassette_tape",
            name = "Vocaloid Mixtape Tape",
            description = "A retro transparent green tape with GUMI & VN tracks.",
            price = 15,
            category = ShopCategory.GIFT,
            affectionBonus = 10,
            moodEffect = NamiMood.LOVE,
            drawableRes = R.drawable.item_cassette_tape,
        ),
        ShopItem(
            id = "four_leaf_clover",
            name = "Four-Leaf Clover Charm",
            description = "A real lucky clover preserved in a crystal pendant.",
            price = 10,
            category = ShopCategory.GIFT,
            affectionBonus = 7,
            moodEffect = NamiMood.LOVE,
            drawableRes = R.drawable.item_clover_pendant,
        ),
        ShopItem(
            id = "calico_keychain",
            name = "Calico Cat Bell Charm",
            description = "A cute porcelain kitty keychain with a pleasant chime.",
            price = 8,
            category = ShopCategory.GIFT,
            affectionBonus = 6,
            moodEffect = NamiMood.HAPPY,
            drawableRes = R.drawable.item_calico_keychain,
        ),
        ShopItem(
            id = "photo_album",
            name = "Photo Album",
            description = "A small album for memories. First page is already open.",
            price = 20,
            category = ShopCategory.GIFT,
            affectionBonus = 12,
            moodEffect = NamiMood.LOVE,
        ),

        // Mood & Atmosphere
        ShopItem(
            id = "rain_forecast",
            name = "Rain Forecast",
            description = "Shows a rainy window. Nami loves listening to droplets.",
            price = 5,
            category = ShopCategory.MOOD,
            moodEffect = NamiMood.THINKING,
            energyRestore = 10,
        ),
        ShopItem(
            id = "bear_chan_photo",
            name = "Bear-chan Photo",
            description = "A mysterious photo. How did this get here?!",
            price = 3,
            category = ShopCategory.MOOD,
            moodEffect = NamiMood.PANIC,
        ),
    )

    fun getItem(id: String): ShopItem? = items.find { it.id == id }
}
