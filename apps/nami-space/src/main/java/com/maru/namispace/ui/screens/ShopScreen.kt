package com.maru.namispace.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.engine.GameManager
import com.maru.namispace.model.ShopCatalog
import com.maru.namispace.model.ShopCategory
import com.maru.namispace.model.ShopItem
import com.maru.namispace.ui.components.OverlaySheet
import com.maru.namispace.ui.theme.*

@Composable
fun ShopOverlay(
    gameManager: GameManager,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val session by gameManager.state.collectAsState()
    var selectedCategory by remember { mutableStateOf<ShopCategory?>(null) }
    var showConfirm by remember { mutableStateOf<ShopItem?>(null) }
    var showUseItem by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val filteredItems = if (selectedCategory != null) {
        ShopCatalog.items.filter { it.category == selectedCategory }
    } else {
        ShopCatalog.items
    }

    OverlaySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Shop & Pantry",
        subtitle = "Get treats and gifts for Nanami",
        maxHeightFraction = 0.82f,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Balance card
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = NamiAccent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MoodHappy,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("NamiCoins", color = NamiMuted, fontSize = 11.sp)
                                Text(
                                    "${session.currency.coins}",
                                    color = MoodHappy,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Earned: ${session.currency.totalEarned}",
                                color = NamiMuted,
                                fontSize = 11.sp,
                            )
                            Text(
                                text = "Spent: ${session.currency.totalSpent}",
                                color = NamiMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            // Category filter chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CategoryChip(
                        label = "All",
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        modifier = Modifier.weight(1f),
                    )
                    ShopCategory.entries.forEach { cat ->
                        CategoryChip(
                            label = "${cat.icon} ${cat.label}",
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Items list
            items(filteredItems, key = { it.id }) { item ->
                val ownedCount = session.inventory[item.id] ?: 0
                val canAfford = session.currency.coins >= item.price

                ShopItemCard(
                    item = item,
                    ownedCount = ownedCount,
                    canAfford = canAfford,
                    onBuy = { showConfirm = item },
                    onUse = { showUseItem = item.id to ownedCount },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Buy confirmation dialog
    showConfirm?.let { item ->
        AlertDialog(
            onDismissRequest = { showConfirm = null },
            containerColor = NamiPanel,
            title = { Text("Buy ${item.name}?", color = NamiText, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(text = item.description, color = NamiMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Price: ★${item.price}",
                        color = MoodHappy,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        gameManager.buyItem(item.id)
                        showConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = NamiAccent),
                ) {
                    Text("Buy", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirm = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = NamiMuted),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // Use item dialog
    showUseItem?.let { (itemId, count) ->
        val item = ShopCatalog.getItem(itemId)
        if (item != null && count > 0) {
            AlertDialog(
                onDismissRequest = { showUseItem = null },
                containerColor = NamiPanel,
                title = { Text("Give ${item.name} to Nami?", color = NamiText, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = item.description, color = NamiMuted, fontSize = 13.sp)
                        Text(text = "Owned: $count", color = NamiAccent, fontSize = 12.sp)
                        if (item.hungerRestore > 0) {
                            Text("🍽 Hunger +${item.hungerRestore}", color = NamiMuted, fontSize = 12.sp)
                        }
                        if (item.energyRestore > 0) {
                            Text("⚡ Energy +${item.energyRestore}", color = NamiMuted, fontSize = 12.sp)
                        }
                        if (item.affectionBonus > 0) {
                            Text("♡ Affection +${item.affectionBonus}", color = NamiBlush, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            gameManager.consumeItem(itemId)
                            showUseItem = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = NamiAccent),
                    ) {
                        Text("Use", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showUseItem = null },
                        colors = ButtonDefaults.textButtonColors(contentColor = NamiMuted),
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) NamiAccent.copy(alpha = 0.22f) else NamiPanel.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, if (selected) NamiAccent.copy(alpha = 0.5f) else NamiBorder),
    ) {
        Text(
            text = label,
            color = if (selected) NamiAccent else NamiMuted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun ShopItemCard(
    item: ShopItem,
    ownedCount: Int,
    canAfford: Boolean,
    onBuy: () -> Unit,
    onUse: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = NamiPanel.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, NamiBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NamiAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.drawableRes != null) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = item.drawableRes),
                        contentDescription = item.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                } else {
                    Text(text = item.category.icon, fontSize = 22.sp)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    color = NamiText,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.description,
                    color = NamiMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                if (ownedCount > 0) {
                    Text(
                        text = "Owned: $ownedCount",
                        color = NamiAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "★${item.price}",
                    color = if (canAfford) MoodHappy else NamiMuted,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (ownedCount > 0 && item.isConsumable) {
                        Surface(
                            onClick = onUse,
                            shape = RoundedCornerShape(8.dp),
                            color = NamiAccent.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                "Use",
                                color = NamiAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Surface(
                        onClick = onBuy,
                        enabled = canAfford,
                        shape = RoundedCornerShape(8.dp),
                        color = if (canAfford) NamiRibbon.copy(alpha = 0.3f) else NamiBorder.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (canAfford) NamiRibbon.copy(alpha = 0.6f) else Color.Transparent),
                    ) {
                        Text(
                            "Buy",
                            color = if (canAfford) NamiText else NamiMuted.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
