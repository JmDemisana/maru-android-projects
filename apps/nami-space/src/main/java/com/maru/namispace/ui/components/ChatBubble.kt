package com.maru.namispace.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.model.ChatMessage
import com.maru.namispace.ui.theme.*
import java.util.Locale

@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val isUser = message.isFromUser
    val bubbleColor = if (isUser) NamiAccent.copy(alpha = 0.15f) else NamiPanel
    val textColor = if (isUser) NamiAccent else NamiText
    val alignment = if (isUser) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.88f)
    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = alignment
                .clip(shape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.content,
                color = textColor,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            if (!isUser && message.durationMs != null && message.durationMs > 0) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⚡ ${String.format(Locale.US, "%.1fs", message.durationMs / 1000f)}",
                        color = NamiMuted.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
