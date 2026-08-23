package io.maru.marucast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.marucast.R
import io.maru.marucast.media.MarucastNotificationListener
import io.maru.marucast.media.MediaSessionState
import io.maru.marucast.service.MarucastForegroundService

// -------------------------------------------------------------------------------------------------
// 🎨 MARUCAST FLAT-GLASS COLOR PALETTE & SHAPES
// -------------------------------------------------------------------------------------------------
val MaruDarkBg = Color(0xFF07090E)
val MaruGlassCardBg = Color(0xFF101420).copy(alpha = 0.72f)
val MaruGlassSubtleBg = Color(0xFF161B2E).copy(alpha = 0.50f)
val MaruGlassBorder = Color(0xFF3B4866).copy(alpha = 0.45f)
val MaruGlassBorderSoft = Color(0xFF283147).copy(alpha = 0.35f)
val MaruAccentPink = Color(0xFFFF5C93)
val MaruAccentBlue = Color(0xFF38BDF8)
val MaruAccentPurple = Color(0xFFA78BFA)
val MaruAccentGreen = Color(0xFF34D399)
val MaruAccentAmber = Color(0xFFFBBF24)
val MaruTextStrong = Color(0xFFF1F5F9)
val MaruTextMuted = Color(0xFF94A3B8)

val MaruCardShape = RoundedCornerShape(20.dp)
val MaruPillShape = RoundedCornerShape(999.dp)

// Space Grotesk Typography
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
    Font(R.font.space_grotesk_bold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.ExtraBold),
    Font(R.font.space_grotesk_bold, FontWeight.Black)
)

val MaruTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk),
    displayMedium = TextStyle(fontFamily = SpaceGrotesk),
    displaySmall = TextStyle(fontFamily = SpaceGrotesk),
    headlineLarge = TextStyle(fontFamily = SpaceGrotesk),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk),
    titleSmall = TextStyle(fontFamily = SpaceGrotesk),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk),
    labelLarge = TextStyle(fontFamily = SpaceGrotesk),
    labelMedium = TextStyle(fontFamily = SpaceGrotesk),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk)
)

// -------------------------------------------------------------------------------------------------
// 💎 REUSABLE GLASS COMPONENTS
// -------------------------------------------------------------------------------------------------
@Composable
fun MaruGlassCard(
    modifier: Modifier = Modifier,
    border: Color = MaruGlassBorderSoft,
    glowColor: Color = Color.Transparent,
    shape: Shape = MaruCardShape,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (glowColor != Color.Transparent) {
                    Modifier.border(1.dp, glowColor, shape)
                } else Modifier
            ),
        shape = shape,
        color = MaruGlassCardBg,
        border = BorderStroke(1.dp, border)
    ) {
        Column(content = content)
    }
}

@Composable
fun MaruGlassSectionHeader(title: String, icon: ImageVector, accentColor: Color = MaruAccentBlue) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accentColor, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                fontSize = 11.sp
            ),
            color = accentColor
        )
    }
}

// -------------------------------------------------------------------------------------------------
// 📱 MAIN MARUCAST APP CONTENT
// -------------------------------------------------------------------------------------------------
@Composable
fun MarucastAppContent(
    modifier: Modifier = Modifier,
    onStartStream: (String, onResult: (Boolean, String?) -> Unit) -> Unit,
    onStopStream: () -> Unit
) {
    val context = LocalContext.current
    var hasNotificationAccess by remember { mutableStateOf(isNotificationServiceEnabled(context)) }
    var currentToken by remember { mutableStateOf(MarucastForegroundService.currentToken) }

    LaunchedEffect(Unit) {
        while (true) {
            hasNotificationAccess = isNotificationServiceEnabled(context)
            currentToken = MarucastForegroundService.currentToken
            kotlinx.coroutines.delay(1000)
        }
    }

    MaterialTheme(typography = MaruTypography) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = SpaceGrotesk)) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(MaruDarkBg)
            ) {
                // Ambient decorative glow spots
                Box(
                    modifier = Modifier
                        .size(320.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 100.dp, y = (-80).dp)
                        .background(Brush.radialGradient(listOf(MaruAccentBlue.copy(alpha = 0.14f), Color.Transparent)))
                )
                Box(
                    modifier = Modifier
                        .size(340.dp)
                        .align(Alignment.BottomStart)
                        .offset(x = (-100).dp, y = 100.dp)
                        .background(Brush.radialGradient(listOf(MaruAccentPink.copy(alpha = 0.12f), Color.Transparent)))
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_maru_heart),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "MARUCAST",
                                    color = MaruAccentBlue,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.5.sp
                                )
                                Text(
                                    text = "AUDIO BROADCASTER",
                                    color = MaruTextMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }

                        // Live Status Badge
                        val isCasting = currentToken != null
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isCasting) MaruAccentGreen.copy(alpha = 0.16f) else MaruGlassSubtleBg,
                                    MaruPillShape
                                )
                                .border(
                                    1.dp,
                                    if (isCasting) MaruAccentGreen.copy(alpha = 0.5f) else MaruGlassBorderSoft,
                                    MaruPillShape
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isCasting) MaruAccentGreen else MaruTextMuted)
                                )
                                Text(
                                    text = if (isCasting) "CASTING" else "READY TO PAIR",
                                    color = if (isCasting) MaruAccentGreen else MaruTextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    if (!hasNotificationAccess) {
                        PermissionPromptCard {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    } else {
                        AnimatedContent(
                            targetState = currentToken,
                            transitionSpec = {
                                fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                            },
                            label = "screen_transition"
                        ) { token ->
                            if (token == null) {
                                PairingScreen(
                                    onPairCodeEntered = { pin, onResult ->
                                        onStartStream(pin, onResult)
                                    }
                                )
                            } else {
                                NowPlayingScreen(
                                    onDisconnect = {
                                        onStopStream()
                                        currentToken = null
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// ⚠️ NOTIFICATION PERMISSION PROMPT CARD
// -------------------------------------------------------------------------------------------------
@Composable
fun PermissionPromptCard(onGrantClick: () -> Unit) {
    MaruGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 30.dp),
        border = MaruAccentAmber.copy(alpha = 0.45f),
        glowColor = MaruAccentAmber.copy(alpha = 0.15f)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaruAccentAmber.copy(alpha = 0.15f))
                    .border(1.dp, MaruAccentAmber.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = MaruAccentAmber,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Notification Access Required",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaruTextStrong,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Marucast needs Notification Listener access to detect active media playback, sync track titles & artist names, and transmit cover artwork to your receiver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaruTextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaruAccentAmber),
                shape = MaruPillShape,
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Text(
                    "Grant Permission",
                    color = MaruDarkBg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 🔢 PAIRING SCREEN (PIN INPUT & NUMPAD)
// -------------------------------------------------------------------------------------------------
@Composable
fun PairingScreen(onPairCodeEntered: (String, onResult: (Boolean, String?) -> Unit) -> Unit) {
    var pinText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var audioSourceIsMic by remember { mutableStateOf(MarucastForegroundService.isMicMode) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Pair with Web Receiver",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaruTextStrong
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Open the Marucast applet on your PC/TV and enter the 6-digit PIN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaruTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Audio Source Selector (System Music vs Microphone)
            Row(
                modifier = Modifier
                    .background(MaruGlassSubtleBg, MaruPillShape)
                    .border(1.dp, MaruGlassBorderSoft, MaruPillShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val modes = listOf(
                    false to ("System Music" to Icons.Default.MusicNote),
                    true to ("Microphone" to Icons.Default.Mic)
                )
                modes.forEach { (isMic, info) ->
                    val (label, icon) = info
                    val selected = audioSourceIsMic == isMic
                    Box(
                        modifier = Modifier
                            .clip(MaruPillShape)
                            .background(if (selected) MaruAccentBlue.copy(alpha = 0.22f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (selected) MaruAccentBlue else Color.Transparent,
                                MaruPillShape
                            )
                            .clickable {
                                audioSourceIsMic = isMic
                                MarucastForegroundService.isMicMode = isMic
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = if (selected) MaruAccentBlue else MaruTextMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = label,
                                color = if (selected) MaruAccentBlue else MaruTextMuted,
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 6-digit PIN Input Boxes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until 6) {
                    val char = pinText.getOrNull(i)
                    val isCurrentSlot = pinText.length == i
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.9f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (char != null) MaruAccentBlue.copy(alpha = 0.12f) else MaruGlassSubtleBg)
                            .border(
                                BorderStroke(
                                    if (char != null || isCurrentSlot) 1.5.dp else 1.dp,
                                    if (char != null) MaruAccentBlue else if (isCurrentSlot) MaruAccentPink else MaruGlassBorderSoft
                                ),
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = char?.toString() ?: "",
                            color = MaruAccentBlue,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            if (pinText.isNotEmpty() || isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isLoading) "Cancel" else "Clear All",
                    color = if (isLoading) MaruAccentPink else MaruTextMuted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(MaruPillShape)
                        .clickable {
                            isLoading = false
                            pinText = ""
                            errorMessage = null
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = error,
                    color = MaruAccentPink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            if (isLoading) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = MaruAccentBlue, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            }
        }

        // Custom Tactile Number Pad
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("CLR", "0", "OK")
            )
            keys.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                            onPairCodeEntered(pinText) { success, error ->
                                                isLoading = false
                                                if (!success) errorMessage = error ?: "Failed to pair"
                                            }
                                        } else {
                                            errorMessage = "Enter exactly 6 digits."
                                        }
                                    }
                                    else -> {
                                        if (pinText.length < 6) pinText += key
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (key == "OK") MaruAccentBlue.copy(alpha = 0.22f) else MaruGlassSubtleBg,
                            border = BorderStroke(
                                1.dp,
                                if (key == "OK") MaruAccentBlue else MaruGlassBorderSoft
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = key,
                                    color = when (key) {
                                        "OK" -> MaruAccentBlue
                                        "CLR" -> MaruAccentPink
                                        else -> MaruTextStrong
                                    },
                                    fontSize = if (isAction) 15.sp else 19.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "with <3, Maru & Nanami",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.5.sp,
                    letterSpacing = 0.5.sp,
                    color = MaruTextMuted.copy(alpha = 0.5f)
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 🎵 REALTIME AUDIO EQUALIZER VISUALIZER
// -------------------------------------------------------------------------------------------------
@Composable
fun AudioVisualizer(isPlaying: Boolean) {
    Row(
        modifier = Modifier
            .height(26.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val barCount = 9
        val infiniteTransition = rememberInfiniteTransition(label = "audio_visualizer")

        for (i in 0 until barCount) {
            val duration = remember { (400..900).random() }
            val heightFactor by if (isPlaying) {
                infiniteTransition.animateFloat(
                    initialValue = 0.15f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(duration, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bar_height_$i"
                )
            } else {
                remember { mutableStateOf(0.12f) }
            }

            Box(
                modifier = Modifier
                    .width(4.5.dp)
                    .fillMaxHeight(heightFactor)
                    .clip(MaruPillShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(MaruAccentPink, MaruAccentPurple, MaruAccentBlue)
                        )
                    )
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 📻 NOW PLAYING SCREEN
// -------------------------------------------------------------------------------------------------
@Composable
fun NowPlayingScreen(onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("marucast_prefs", Context.MODE_PRIVATE) }
    var delayMode by remember { mutableStateOf(prefs.getString("delay_mode", "lossless") ?: "lossless") }
    var lyricsDelayOffset by remember { mutableStateOf(prefs.getLong("lyrics_delay_offset", 0L)) }
    var title by remember { mutableStateOf(MediaSessionState.title) }
    var artist by remember { mutableStateOf(MediaSessionState.artist) }
    var appLabel by remember { mutableStateOf(MediaSessionState.appLabel) }
    var isPlaying by remember { mutableStateOf(MediaSessionState.isPlaying) }
    var artworkBitmap by remember { mutableStateOf(MediaSessionState.artworkBitmap) }
    var karaokeEnabled by remember { mutableStateOf(MarucastForegroundService.isKaraokeMode) }

    LaunchedEffect(lyricsDelayOffset) {
        MarucastForegroundService.lyricsDelayOffsetMs = lyricsDelayOffset
    }

    LaunchedEffect(Unit) {
        MediaSessionState.onMetadataChanged = {
            title = MediaSessionState.title
            artist = MediaSessionState.artist
            appLabel = MediaSessionState.appLabel
            isPlaying = MediaSessionState.isPlaying
            artworkBitmap = MediaSessionState.artworkBitmap
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "now_playing_animations")

    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aurora_pulse"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aurora_rotate"
    )

    val currentGlowScale = if (isPlaying) glowScale else 1.0f
    val currentRotation = if (isPlaying) rotationAngle else 0.0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Main Now Playing Card
        MaruGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            border = if (isPlaying) MaruAccentBlue.copy(alpha = 0.35f) else MaruGlassBorderSoft
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Album Art Frame with Neon Aura
                Box(
                    modifier = Modifier.size(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Pulsing Neon Aura behind the art
                    Box(
                        modifier = Modifier
                            .size(185.dp)
                            .scale(currentGlowScale)
                            .rotate(currentRotation)
                            .blur(22.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    colors = listOf(MaruAccentBlue, MaruAccentPink, MaruAccentPurple, MaruAccentBlue)
                                )
                            )
                    )

                    // Album Art Box
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaruDarkBg)
                            .border(1.5.dp, MaruGlassBorder, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (artworkBitmap != null) {
                            Image(
                                bitmap = artworkBitmap!!.asImageBitmap(),
                                contentDescription = "Album Artwork",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            // Spinning vinyl record with Maru Heart sticker
                            Box(
                                modifier = Modifier
                                    .size(130.dp)
                                    .rotate(currentRotation)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0C101A))
                                    .border(2.dp, MaruGlassBorderSoft, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(95.dp)
                                        .border(1.dp, Color(0x14FFFFFF), CircleShape)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(65.dp)
                                        .border(1.dp, Color(0x1AFFFFFF), CircleShape)
                                )
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_maru_heart),
                                    contentDescription = null,
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Realtime Audio Equalizer
                AudioVisualizer(isPlaying = isPlaying)

                Spacer(modifier = Modifier.height(14.dp))

                // Track Title & Artist
                AnimatedContent(
                    targetState = title to artist,
                    transitionSpec = {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    },
                    label = "track_details_transition"
                ) { (currentTitle, currentArtist) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = currentTitle ?: "Waiting for music...",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaruTextStrong,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentArtist ?: "Play a song on your phone",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaruTextMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                // Media Player Source Badge
                appLabel?.let { label ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .background(MaruAccentPurple.copy(alpha = 0.15f), MaruPillShape)
                            .border(1.dp, MaruAccentPurple.copy(alpha = 0.4f), MaruPillShape)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label.uppercase(),
                            color = MaruAccentPurple,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Interactive Media Player Transport Controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous Button
                    Surface(
                        onClick = {
                            MediaSessionState.activeController?.transportControls?.skipToPrevious()
                        },
                        shape = CircleShape,
                        color = MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, MaruGlassBorderSoft),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous",
                                tint = MaruTextStrong,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Play/Pause Button
                    Surface(
                        onClick = {
                            val controller = MediaSessionState.activeController
                            if (isPlaying) {
                                controller?.transportControls?.pause()
                            } else {
                                controller?.transportControls?.play()
                            }
                        },
                        shape = CircleShape,
                        color = MaruAccentBlue.copy(alpha = 0.25f),
                        border = BorderStroke(1.5.dp, MaruAccentBlue),
                        modifier = Modifier.size(62.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaruAccentBlue,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // Next Button
                    Surface(
                        onClick = {
                            MediaSessionState.activeController?.transportControls?.skipToNext()
                        },
                        shape = CircleShape,
                        color = MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, MaruGlassBorderSoft),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                tint = MaruTextStrong,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // Karaoke Mode Glass Card
        MaruGlassSectionHeader("VOCAL PROCESSING", Icons.Default.GraphicEq, MaruAccentPurple)
        MaruGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    karaokeEnabled = !karaokeEnabled
                    MarucastForegroundService.isKaraokeMode = karaokeEnabled
                },
            border = if (karaokeEnabled) MaruAccentPurple.copy(alpha = 0.45f) else MaruGlassBorderSoft
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "🎤",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Column {
                        Text(
                            text = "Karaoke Mode",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaruTextStrong
                        )
                        Text(
                            text = "Real-time center-channel vocal cancellation filter",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaruTextMuted
                        )
                    }
                }

                Switch(
                    checked = karaokeEnabled,
                    onCheckedChange = { checked ->
                        karaokeEnabled = checked
                        MarucastForegroundService.isKaraokeMode = checked
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaruDarkBg,
                        checkedTrackColor = MaruAccentPurple,
                        uncheckedThumbColor = MaruTextMuted,
                        uncheckedTrackColor = MaruGlassSubtleBg
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Lyrics Sync Card
        MaruGlassSectionHeader("TIMING & SYNC", Icons.Default.Sync, MaruAccentPink)
        MaruGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⏱️",
                        fontSize = 20.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Column {
                        Text(
                            text = "Lyrics Sync Nudge",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaruTextStrong
                        )
                        Text(
                            text = "Nudge lyrics backward or forward in real-time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaruTextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = {
                            lyricsDelayOffset = (lyricsDelayOffset - 250).coerceIn(-10000L, 10000L)
                            prefs.edit().putLong("lyrics_delay_offset", lyricsDelayOffset).apply()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, MaruGlassBorderSoft),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("-0.25s", color = MaruTextStrong, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val offsetSecs = lyricsDelayOffset / 1000.0
                        val text = if (lyricsDelayOffset == 0L) "Synced" else "${if (offsetSecs > 0) "+" else ""}${String.format("%.2fs", offsetSecs)}"
                        Text(
                            text = text,
                            color = if (lyricsDelayOffset == 0L) MaruAccentBlue else MaruAccentPink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Surface(
                        onClick = {
                            lyricsDelayOffset = (lyricsDelayOffset + 250).coerceIn(-10000L, 10000L)
                            prefs.edit().putLong("lyrics_delay_offset", lyricsDelayOffset).apply()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, MaruGlassBorderSoft),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("+0.25s", color = MaruTextStrong, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Delay Management Card
        MaruGlassSectionHeader("STREAM QUALITY & BUFFER", Icons.Default.Speed, MaruAccentBlue)
        MaruGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                val delayOptions = listOf(
                    "lossless" to "Lossless",
                    "automatic" to "Automatic",
                    "less_delay" to "Less Delay"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaruGlassSubtleBg, RoundedCornerShape(14.dp))
                        .border(1.dp, MaruGlassBorderSoft, RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    delayOptions.forEach { (mode, label) ->
                        val selected = delayMode == mode
                        Surface(
                            onClick = {
                                delayMode = mode
                                MarucastForegroundService.delayManagementMode = mode
                                prefs.edit().putString("delay_mode", mode).apply()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaruAccentBlue.copy(alpha = 0.22f) else Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaruAccentBlue else Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    color = if (selected) MaruAccentBlue else MaruTextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when (delayMode) {
                        "lossless" -> "• Lossless: Prioritizes uncompressed, bit-perfect sound fidelity."
                        "automatic" -> "• Automatic: Monitors network jitter and adapts buffer dynamically."
                        else -> "• Less Delay: Low-latency streaming optimized for responsive playback."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaruTextMuted,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Disconnect Button
        Surface(
            onClick = onDisconnect,
            shape = MaruPillShape,
            color = MaruAccentPink.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, MaruAccentPink.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        tint = MaruAccentPink,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Stop Broadcast",
                        color = MaruAccentPink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "with <3, Maru & Nanami",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                letterSpacing = 0.5.sp,
                color = MaruTextMuted.copy(alpha = 0.5f)
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val cn = ComponentName(context, MarucastNotificationListener::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(cn.flattenToString())
}
