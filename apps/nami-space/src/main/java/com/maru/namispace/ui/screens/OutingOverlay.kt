package com.maru.namispace.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.engine.GameManager
import com.maru.namispace.model.LocationMode
import com.maru.namispace.model.OutingCatalog
import com.maru.namispace.model.OutingEncounter
import com.maru.namispace.ui.theme.*

@Composable
fun OutingOverlay(
    gameManager: GameManager,
    onClose: () -> Unit,
) {
    val session by gameManager.state.collectAsState()
    var isWalking by remember { mutableStateOf(false) }

    // Simulated walking progress loop
    LaunchedEffect(isWalking) {
        while (isWalking) {
            kotlinx.coroutines.delay(2000)
            gameManager.addOutingDistance(25f) // +25 meters per tick
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60A0D14)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = NamiPanel,
            border = BorderStroke(1.dp, NamiBorder),
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(NamiAccent.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "🚶‍♀️", fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Taking Nami on an Outing",
                                color = NamiText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Walk together to trigger street encounters & souvenirs",
                                color = NamiMuted,
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = NamiMuted)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // GPS Walk Distance Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = NamiPanel,
                    border = BorderStroke(1.dp, NamiBorder.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "TOTAL WALK DISTANCE",
                            color = NamiMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${session.outingDistanceMeters.toInt()} m",
                            color = NamiAccent,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            text = "~ ${(session.outingDistanceMeters * 1.35f).toInt()} steps taken with Nanami",
                            color = NamiText.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                        )

                        Spacer(Modifier.height(14.dp))

                        // Walk toggle button
                        Button(
                            onClick = { isWalking = !isWalking },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isWalking) NamiBlush else NamiAccent,
                                contentColor = NamiDeep,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) {
                            Text(
                                text = if (isWalking) "Pause Walk ⏸" else "Start Walking Together ▶",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Active Encounter Alert (if triggered)
                session.pendingEncounter?.let { enc ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = NamiRibbon.copy(alpha = 0.25f),
                        border = BorderStroke(1.5.dp, NamiAccent),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = enc.icon, fontSize = 24.sp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Outing Encounter!",
                                        color = NamiAccent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = enc.title,
                                        color = NamiText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = enc.description,
                                color = NamiText.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { gameManager.claimEncounter(enc) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Claim (+★${enc.coinsReward}, +${enc.affectionBonus}♡)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { gameManager.dismissEncounter() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NamiMuted),
                                    modifier = Modifier.width(80.dp),
                                ) {
                                    Text("Pass", fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // Souvenirs Scrapbook
                Text(
                    text = "Outing Souvenir Scrapbook (${session.collectedSouvenirs.size}/${OutingCatalog.encounters.size})",
                    color = NamiText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(OutingCatalog.encounters) { item ->
                        val unlocked = item.id in session.collectedSouvenirs
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (unlocked) NamiPanel else NamiPanel.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, if (unlocked) NamiAccent.copy(alpha = 0.6f) else NamiBorder.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (unlocked) item.icon else "❓",
                                    fontSize = 20.sp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (unlocked) (item.souvenir ?: item.title) else "Undiscovered Souvenir",
                                        color = if (unlocked) NamiText else NamiMuted,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = if (unlocked) item.description else "Keep walking with Nanami to discover this item!",
                                        color = NamiMuted,
                                        fontSize = 10.5.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
