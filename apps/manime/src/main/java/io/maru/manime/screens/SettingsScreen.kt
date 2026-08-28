package io.maru.manime.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.maru.manime.*

@Composable
fun SettingsScreen(
    reportProgress: Boolean,
    onToggleReportProgress: (Boolean) -> Unit,
    rememberPosition: Boolean,
    onToggleRememberPosition: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "player_settings") {
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassSectionHeader(
                        title = "PLAYBACK & PROGRESS",
                        icon = Icons.Default.Settings,
                        color = MaruAccentPink
                    )

                    GlassFeatureRow(
                        label = "Sync Progress to AniList",
                        checked = reportProgress,
                        onCheckedChange = onToggleReportProgress
                    )

                    HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Remember Playback Position",
                        checked = rememberPosition,
                        onCheckedChange = onToggleRememberPosition
                    )
                }
            }
        }
    }
}
