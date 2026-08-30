package io.maru.manime.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*
import io.maru.manime.extensions.StreamLink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityPickerSheet(
    streams: List<StreamLink>,
    animeTitle: String,
    isSearching: Boolean = false,
    onSearchCloudstream: (String) -> Unit,
    onSelectStream: (StreamLink) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedAudioFilter by remember { mutableStateOf("ALL") } // "ALL" | "SUB" | "DUB"
    var selectedMethodFilter by remember { mutableStateOf("ALL") } // "ALL" | "TORRENT" | "STREMIO" | "CLOUDSTREAM" | "ANIYOMI"

    val filteredStreams = remember(streams, selectedAudioFilter, selectedMethodFilter) {
        streams.filter { s ->
            val matchAudio = when (selectedAudioFilter) {
                "SUB" -> s.audioType == "SUB" || s.audioType == "DUAL_AUDIO"
                "DUB" -> s.audioType == "DUB" || s.audioType == "DUAL_AUDIO"
                else -> true
            }
            val matchMethod = when (selectedMethodFilter) {
                "TORRENT" -> s.methodType == "TORRENT"
                "STREMIO" -> s.methodType == "STREMIO"
                "CLOUDSTREAM" -> s.methodType == "CLOUDSTREAM"
                "ANIYOMI" -> s.methodType == "ANIYOMI"
                else -> true
            }
            matchAudio && matchMethod
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaruCosmicMid,
        contentColor = MaruTextStrong,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = MaruGlassBorderSoft)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SELECT STREAM SOURCE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        ),
                        color = MaruAccentPink
                    )
                    Text(
                        text = if (isSearching) "Searching streams... (${streams.size} found)" else "${streams.size} streams found • Soft subs enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSearching) MaruAccentBlue else MaruTextMuted
                    )
                }
                if (isSearching) {
                    CircularProgressIndicator(
                        color = MaruAccentBlue,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sub vs Dub Filter Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL" to "ALL AUDIO", "SUB" to "🇯🇵 SUB ONLY", "DUB" to "🇺🇸 DUB / DUAL").forEach { (key, label) ->
                    val isSelected = selectedAudioFilter == key
                    Surface(
                        onClick = { selectedAudioFilter = key },
                        shape = MaruPillShape,
                        color = if (isSelected) Color(0x33E85D9F) else MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (isSelected) MaruAccentPink else MaruTextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Method Filter Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "ALL" to "All Methods",
                    "TORRENT" to "P2P Torrent",
                    "STREMIO" to "Stremio HTTP",
                    "CLOUDSTREAM" to "Cloudstream",
                    "ANIYOMI" to "Aniyomi"
                ).forEach { (key, label) ->
                    val isSelected = selectedMethodFilter == key
                    Surface(
                        onClick = { selectedMethodFilter = key },
                        shape = MaruPillShape,
                        color = if (isSelected) Color(0x3360E2FF) else Color.Transparent,
                        border = BorderStroke(1.dp, if (isSelected) MaruAccentBlue else MaruGlassBorderSoft)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                            color = if (isSelected) MaruAccentBlue else MaruTextMuted.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (filteredStreams.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                color = MaruAccentPink,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Searching streams... (${streams.size})",
                                color = MaruTextStrong,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Checking Stremio, Cloudstream & Aniyomi sources",
                                color = MaruTextMuted,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                text = if (streams.isEmpty()) "No streams found." else "No streams match the selected audio/method filter.",
                                color = MaruTextMuted,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            Surface(
                                onClick = { onSearchCloudstream(animeTitle) },
                                shape = MaruPillShape,
                                color = Color(0xFF1D4ED8),
                                border = BorderStroke(1.dp, Color(0xFF60A5FA))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Search in Cloudstream App", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.5.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredStreams) { stream ->
                        StreamItemCard(stream = stream, onClick = { onSelectStream(stream) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamItemCard(
    stream: StreamLink,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaruCardShape,
        color = MaruGlassCardBg,
        border = BorderStroke(1.dp, MaruGlassBorderSoft),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Resolution Badge
                    Surface(
                        color = when (stream.quality) {
                            "4K" -> Color(0x33B388FF)
                            "1080p" -> Color(0x33E85D9F)
                            "720p" -> Color(0x3360E2FF)
                            else -> Color(0x334ADE80)
                        },
                        shape = MaruInputShape,
                        border = BorderStroke(1.dp, when (stream.quality) {
                            "4K" -> MaruAccentPurple
                            "1080p" -> MaruAccentPink
                            "720p" -> MaruAccentBlue
                            else -> MaruAccentGreen
                        })
                    ) {
                        Text(
                            text = stream.quality,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (stream.quality) {
                                "4K" -> MaruAccentPurple
                                "1080p" -> MaruAccentPink
                                "720p" -> MaruAccentBlue
                                else -> MaruAccentGreen
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Audio Type Pill (SUB / DUB / DUAL)
                    Surface(
                        color = if (stream.audioType == "DUB" || stream.audioType == "DUAL_AUDIO") Color(0x334ADE80) else Color(0x22FFFFFF),
                        shape = MaruPillShape
                    ) {
                        Text(
                            text = when (stream.audioType) {
                                "DUAL_AUDIO" -> "🇯🇵+🇺🇸 DUAL AUDIO"
                                "DUB" -> "🇺🇸 ENG DUB"
                                else -> "🇯🇵 JP SUB"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (stream.audioType == "DUB" || stream.audioType == "DUAL_AUDIO") MaruAccentGreen else MaruTextMuted,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }

                    // Method Badge
                    Surface(
                        color = if (stream.isTorrent) Color(0x33B388FF) else Color(0x2260E2FF),
                        shape = MaruPillShape
                    ) {
                        Text(
                            text = if (stream.isTorrent) "P2P TORRENT" else stream.methodType,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (stream.isTorrent) MaruAccentPurple else MaruAccentBlue,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Seeders count
                if (stream.seeders != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaruAccentGreen, modifier = Modifier.size(13.dp))
                        Text(
                            text = "${stream.seeders} seeds",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaruAccentGreen
                        )
                    }
                }
            }

            if (!stream.filename.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stream.filename,
                    fontSize = 12.sp,
                    color = MaruTextStrong,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stream.sourceName,
                    fontSize = 11.sp,
                    color = MaruTextMuted
                )
                if (stream.formatBadge != null) {
                    Text(
                        text = stream.formatBadge,
                        fontSize = 10.sp,
                        color = MaruAccentBlue
                    )
                }
            }
        }
    }
}
