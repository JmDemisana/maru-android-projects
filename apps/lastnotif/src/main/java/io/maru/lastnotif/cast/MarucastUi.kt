package io.maru.lastnotif.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.lastnotif.*
import io.maru.lastnotif.R

@Composable
fun MarucastScreen(
    modifier: Modifier = Modifier,
    onStartStream: (String, onResult: (Boolean, String?) -> Unit) -> Unit,
    onStopStream: () -> Unit
) {
    val context = LocalContext.current
    var currentToken by remember { mutableStateOf(MarucastForegroundService.currentToken) }

    LaunchedEffect(Unit) {
        while (true) {
            currentToken = MarucastForegroundService.currentToken
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassMasterTile(
            title = "MARUCAST BROADCASTER",
            description = "Stream lossless system audio, track metadata, and live lyrics over local Wi-Fi to Web, PC, and TV receivers.",
            isEnabled = currentToken != null,
            onToggle = { enabled ->
                if (!enabled) {
                    onStopStream()
                    currentToken = null
                }
            }
        )

        AnimatedContent(
            targetState = currentToken != null,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            label = "cast_content_transition"
        ) { isConnected ->
            if (!isConnected) {
                CastPairingSection(
                    onPairCodeEntered = { pin, onResult ->
                        onStartStream(pin) { success, err ->
                            if (success) {
                                currentToken = MarucastForegroundService.currentToken
                            }
                            onResult(success, err)
                        }
                    }
                )
            } else {
                CastNowPlayingSection(
                    onDisconnect = {
                        onStopStream()
                        currentToken = null
                    }
                )
            }
        }
    }
}

@Composable
fun CastPairingSection(
    onPairCodeEntered: (String, onResult: (Boolean, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("marucast_prefs", Context.MODE_PRIVATE) }
    var pinText by remember { mutableStateOf(sharedPrefs.getString("last_pin", "") ?: "") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var audioSourceIsMic by remember { mutableStateOf(MarucastForegroundService.isMicMode) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassSectionHeader("PAIR RECEIVER", Icons.Default.Sensors)

        GlassCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Enter the 6-digit PIN displayed on your Marucast Web or TV receiver applet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaruTextMuted
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaruGlassSubtleBg, MaruPillShape)
                        .border(1.dp, MaruGlassBorderSoft, MaruPillShape)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val modes = listOf(
                        false to ("System Audio" to Icons.Default.MusicNote),
                        true to ("Microphone" to Icons.Default.Mic)
                    )
                    modes.forEach { (isMic, info) ->
                        val (label, icon) = info
                        val selected = audioSourceIsMic == isMic
                        Surface(
                            onClick = {
                                audioSourceIsMic = isMic
                                MarucastForegroundService.isMicMode = isMic
                            },
                            shape = MaruPillShape,
                            color = if (selected) MaruAccentPink.copy(alpha = 0.22f) else Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaruAccentPink else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = if (selected) MaruAccentPink else MaruTextMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = label,
                                    color = if (selected) MaruAccentPink else MaruTextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until 6) {
                        val char = pinText.getOrNull(i)
                        val isCurrentSlot = pinText.length == i
                        Surface(
                            shape = MaruCardShape,
                            color = if (char != null) MaruAccentPink.copy(alpha = 0.15f) else MaruGlassSubtleBg,
                            border = BorderStroke(
                                if (char != null || isCurrentSlot) 1.5.dp else 1.dp,
                                if (char != null) MaruAccentPink else if (isCurrentSlot) MaruAccentBlue else MaruGlassBorderSoft
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(0.9f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = char?.toString() ?: "",
                                    color = MaruAccentPink,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaruDanger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val keys = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("CLR", "0", "OK")
                    )
                    keys.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { key ->
                                val isAction = key == "OK" || key == "CLR"
                                Surface(
                                    onClick = {
                                        if (isLoading) return@Surface
                                        errorMessage = null
                                        when (key) {
                                            "CLR" -> {
                                                if (pinText.isNotEmpty()) pinText = pinText.dropLast(1)
                                            }
                                            "OK" -> {
                                                if (pinText.length == 6) {
                                                    isLoading = true
                                                    errorMessage = null
                                                    sharedPrefs.edit().putString("last_pin", pinText).apply()
                                                    onPairCodeEntered(pinText) { success, error ->
                                                        isLoading = false
                                                        if (!success) errorMessage = error ?: "Failed to pair receiver"
                                                    }
                                                } else {
                                                    errorMessage = "Please enter all 6 digits."
                                                }
                                            }
                                            else -> {
                                                if (pinText.length < 6) pinText += key
                                            }
                                        }
                                    },
                                    shape = MaruInputShape,
                                    color = if (key == "OK") MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                                    border = BorderStroke(
                                        1.dp,
                                        if (key == "OK") MaruAccentPink else MaruGlassBorderSoft
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (key == "OK" && isLoading) {
                                            CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text(
                                                text = key,
                                                color = when (key) {
                                                    "OK" -> MaruAccentPink
                                                    "CLR" -> MaruAccentBlue
                                                    else -> MaruTextStrong
                                                },
                                                fontSize = if (isAction) 13.sp else 17.sp,
                                                fontWeight = FontWeight.Bold
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

        GlassSectionHeader("WAYS TO USE MARUCAST", Icons.Default.Devices)

        // 1. On the Web
        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(MaruAccentPink.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Language, null, tint = MaruAccentPink, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("On the Web", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            Box(
                                modifier = Modifier
                                    .background(MaruAccentGreen.copy(alpha = 0.2f), MaruPillShape)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("READY", color = MaruAccentGreen, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text("Open maruchansquigle.vercel.app/marucast on your PC, laptop, or tablet to get a 6-digit PIN.", color = MaruTextMuted, fontSize = 11.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassButton(
                        onClick = {
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Marucast Web URL", "https://maruchansquigle.vercel.app/marucast")
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "Copied Marucast web receiver URL!", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        },
                        borderColor = MaruGlassBorderSoft,
                        background = MaruGlassSubtleBg,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, null, tint = MaruTextStrong, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("COPY LINK", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    GlassButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maruchansquigle.vercel.app/marucast"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        borderColor = MaruAccentPink,
                        background = MaruAccentPink.copy(alpha = 0.15f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaruAccentPink, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OPEN WEB", color = MaruAccentPink, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 2. On Maru Audio Suite
        GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaruAccentGreen.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = MaruAccentGreen, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("On Maru Audio Suite", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Box(
                            modifier = Modifier
                                .background(MaruAccentGreen.copy(alpha = 0.2f), MaruPillShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("THIS DEVICE", color = MaruAccentGreen, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Broadcast lossless audio, track metadata, and live synced lyrics over Wi-Fi.", color = MaruTextMuted, fontSize = 11.sp)
                }
            }
        }

        // 3. On Marucast for Android TV
        GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaruAccentBlue.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Tv, null, tint = MaruAccentBlue, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("On Marucast for Android TV", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Box(
                            modifier = Modifier
                                .background(MaruAccentBlue.copy(alpha = 0.2f), MaruPillShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("COMING SOON", color = MaruAccentBlue, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Big-screen living room receiver with synchronized lyrics display and remote controls.", color = MaruTextMuted, fontSize = 11.sp)
                }
            }
        }

        // 4. On Marucast for Windows
        GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0xFFC88CFF).copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Laptop, null, tint = Color(0xFFC88CFF), modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("On Marucast for Windows (Tauri)", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFC88CFF).copy(alpha = 0.2f), MaruPillShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("COMING SOON", color = Color(0xFFC88CFF), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Native desktop companion app with low-latency audio capture and virtual mic loopback.", color = MaruTextMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun CastNowPlayingSection(onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("marucast_prefs", Context.MODE_PRIVATE) }
    var lyricsDelayOffset by remember { mutableStateOf(prefs.getLong("lyrics_delay_offset", 0L)) }
    var title by remember { mutableStateOf(CastMediaState.title) }
    var artist by remember { mutableStateOf(CastMediaState.artist) }
    var appLabel by remember { mutableStateOf(CastMediaState.appLabel) }
    var isPlaying by remember { mutableStateOf(CastMediaState.isPlaying) }
    var artworkBitmap by remember { mutableStateOf(CastMediaState.artworkBitmap) }

    LaunchedEffect(lyricsDelayOffset) {
        MarucastForegroundService.lyricsDelayOffsetMs = lyricsDelayOffset
    }

    LaunchedEffect(Unit) {
        CastMediaState.onMetadataChanged = {
            title = CastMediaState.title
            artist = CastMediaState.artist
            appLabel = CastMediaState.appLabel
            isPlaying = CastMediaState.isPlaying
            artworkBitmap = CastMediaState.artworkBitmap
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "aurora")
    val glowScale by infiniteTransition.animateFloat(0.95f, 1.05f, infiniteRepeatable(tween(2000), RepeatMode.Reverse))
    val rotationAngle by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(14000), RepeatMode.Restart))

    val currentGlowScale = if (isPlaying) glowScale else 1.0f
    val currentRotation = if (isPlaying) rotationAngle else 0.0f

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassSectionHeader("NOW STREAMING", Icons.Default.Podcasts)

        GlassCard(
            border = if (isPlaying) MaruAccentPink.copy(alpha = 0.5f) else MaruGlassBorderSoft
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(155.dp)
                                .scale(currentGlowScale)
                                .rotate(currentRotation)
                                .blur(20.dp)
                                .background(
                                    Brush.sweepGradient(listOf(MaruAccentPink.copy(alpha = 0.6f), MaruAccentBlue.copy(alpha = 0.5f), MaruAccentPurple.copy(alpha = 0.6f), MaruAccentPink.copy(alpha = 0.6f))),
                                    CircleShape
                                )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaruCardShape)
                            .background(MaruGlassSubtleBg)
                            .border(1.5.dp, if (isPlaying) MaruAccentPink.copy(alpha = 0.6f) else MaruGlassBorderSoft, MaruCardShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (artworkBitmap != null) {
                            Image(artworkBitmap!!.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(painter = painterResource(id = R.drawable.ic_maru_heart), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(48.dp))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = title ?: "Waiting for Audio...",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaruTextStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = artist ?: "Play music on your device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaruAccentPink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .background(MaruGlassSubtleBg, MaruPillShape)
                        .border(1.dp, MaruGlassBorderSoft, MaruPillShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(text = appLabel ?: "Android Audio", color = MaruTextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { CastMediaState.activeController?.transportControls?.skipToPrevious() }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, null, tint = MaruTextStrong, modifier = Modifier.size(26.dp))
                    }

                    Spacer(Modifier.width(14.dp))

                    Surface(
                        onClick = {
                            if (isPlaying) CastMediaState.activeController?.transportControls?.pause()
                            else CastMediaState.activeController?.transportControls?.play()
                        },
                        shape = CircleShape,
                        color = MaruAccentPink,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    IconButton(onClick = { CastMediaState.activeController?.transportControls?.skipToNext() }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.SkipNext, null, tint = MaruTextStrong, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }

        GlassSectionHeader("STREAM TUNING", Icons.Default.Tune)

        GlassCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stream Quality", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Lossless PCM Wi-Fi broadcast", color = MaruTextMuted, fontSize = 11.sp)
                    }
                    Box(modifier = Modifier.background(MaruAccentBlue.copy(alpha = 0.15f), MaruPillShape).border(1.dp, MaruAccentBlue.copy(alpha = 0.5f), MaruPillShape).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("LOSSLESS", color = MaruAccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = MaruGlassBorderSoft)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Lyrics Delay Offset", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${lyricsDelayOffset}ms", color = MaruAccentPink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(-500L to "-500ms", -100L to "-100ms", 0L to "Reset", 100L to "+100ms", 500L to "+500ms").forEach { (step, label) ->
                            Surface(
                                onClick = {
                                    lyricsDelayOffset = if (step == 0L) 0L else (lyricsDelayOffset + step)
                                    prefs.edit().putLong("lyrics_delay_offset", lyricsDelayOffset).apply()
                                },
                                shape = MaruPillShape,
                                color = MaruGlassSubtleBg,
                                border = BorderStroke(1.dp, MaruGlassBorderSoft),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                    Text(label, color = MaruTextStrong, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        GlassButton(
            onClick = onDisconnect,
            borderColor = MaruDanger.copy(alpha = 0.6f),
            background = MaruDanger.copy(alpha = 0.12f)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Stop, null, tint = MaruDanger, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("DISCONNECT RECEIVER", color = MaruDanger, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
