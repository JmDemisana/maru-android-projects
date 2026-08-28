package io.maru.manime.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.extensions.StreamLink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityPickerSheet(
    streams: List<StreamLink>,
    onSelectStream: (StreamLink) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF100C19),
        contentColor = Color(0xF4F4F9FA),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color(0x60FFFFFF))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Select Stream Quality",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xF4F4F9FA)
            )
            Text(
                text = "Pick an available source to start playback",
                fontSize = 13.sp,
                color = Color(0xB8EBEBF5),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (streams.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No direct stream links found for this episode.\nMake sure you have added Stremio or Cloudstream extensions.",
                        color = Color(0x80FFFFFF),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(streams) { stream ->
                        StreamItemCard(stream = stream, onClick = {
                            onSelectStream(stream)
                        })
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (stream.isTorrent) Icons.Default.Download else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (stream.isTorrent) Color(0xFFFBBF24) else Color(0xFF60E2FF),
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = stream.quality,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Text(
                    text = stream.sourceName,
                    fontSize = 12.sp,
                    color = Color(0x99FFFFFF)
                )
            }
        }

        if (stream.isTorrent) {
            Surface(
                color = Color(0x33FBBF24),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "P2P Torrent",
                    color = Color(0xFFFBBF24),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
