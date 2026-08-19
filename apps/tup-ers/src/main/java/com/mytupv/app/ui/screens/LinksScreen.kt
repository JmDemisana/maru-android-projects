package com.mytupv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LinksScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07040F)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0x26788CFF),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF788CFF).copy(alpha = 0.35f))
            ) {
                Box(Modifier.padding(20.dp)) {
                    Icon(
                        Icons.Outlined.Link, null,
                        tint = Color(0xFF788CFF),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            Text(
                "Links",
                color = Color(0xFFF0EEFF),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Useful TUP links and resources will appear here.",
                color = Color(0x99EBEBF5),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0E0A1A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1A2E))
            ) {
                Text(
                    "Coming soon",
                    color = Color(0x66EBEBF5),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
