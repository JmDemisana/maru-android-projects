package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*

@Composable
fun ProfileScreen(
    user: AniListUser?,
    watchingCount: Int,
    completedCount: Int,
    planningCount: Int,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // User Header Card
        item(key = "user_header") {
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (user?.avatarUrl != null) {
                        AsyncImage(
                            model = user.avatarUrl,
                            contentDescription = user.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaruAccentPink,
                            modifier = Modifier.size(64.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.name ?: "Guest User",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaruTextStrong
                        )
                        Text(
                            text = if (user != null) "AniList Connected" else "Sign in to sync your library",
                            fontSize = 12.sp,
                            color = if (user != null) MaruAccentGreen else MaruTextMuted
                        )
                    }

                    if (user != null) {
                        IconButton(onClick = onLogoutClick) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Log Out", tint = MaruDanger)
                        }
                    } else {
                        GlassButton(
                            onClick = onLoginClick,
                            modifier = Modifier.width(100.dp),
                            background = MaruAccentPink.copy(alpha = 0.2f),
                            borderColor = MaruAccentPink
                        ) {
                            Text("LOGIN", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Stats Row
        if (user != null) {
            item(key = "stats") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatBox(title = "WATCHING", value = "$watchingCount", color = MaruAccentBlue, modifier = Modifier.weight(1f))
                    StatBox(title = "COMPLETED", value = "$completedCount", color = MaruAccentGreen, modifier = Modifier.weight(1f))
                    StatBox(title = "PLANNING", value = "$planningCount", color = MaruAccentPurple, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun StatBox(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaruTextMuted
            )
        }
    }
}
