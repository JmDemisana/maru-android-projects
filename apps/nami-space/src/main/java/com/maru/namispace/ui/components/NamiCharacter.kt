package com.maru.namispace.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.maru.namispace.model.NamiMood
import com.maru.namispace.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun NamiCharacter(
    mood: NamiMood,
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    upClose: Boolean = true,
    scale: Float = 1.58f,
    yOffsetDp: Float = 28f,
) {
    // Subtle organic breathing pulse
    val infiniteTransition = rememberInfiniteTransition(label = "namiBreathe")
    val breatheY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    // Touch reaction bounce
    var touched by remember { mutableStateOf(false) }
    val touchScale by animateFloatAsState(
        targetValue = if (touched) 1.025f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "touch",
    )
    LaunchedEffect(touched) {
        if (touched) {
            delay(160)
            touched = false
        }
    }

    val gestureModifier = if (onTap != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    touched = true
                    onTap()
                },
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .then(gestureModifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Sprite with crossfade transition
        AnimatedContent(
            targetState = mood,
            transitionSpec = {
                fadeIn(tween(350, easing = LinearOutSlowInEasing)) togetherWith
                        fadeOut(tween(180, easing = FastOutLinearInEasing))
            },
            label = "namiSpriteCrossfade",
            modifier = Modifier.fillMaxSize(),
        ) { currentMood ->
            Image(
                painter = painterResource(currentMood.sprite),
                contentDescription = "Nanami - ${currentMood.label}",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val effectiveScale = if (upClose) scale * touchScale else touchScale
                        scaleX = effectiveScale
                        scaleY = effectiveScale
                        // Anchored at upper head/chest so scaling preserves facial framing
                        transformOrigin = TransformOrigin(0.5f, if (upClose) 0.06f else 1f)
                        translationY = breatheY + if (upClose) yOffsetDp else 0f
                    },
                contentScale = if (upClose) ContentScale.Crop else ContentScale.Fit,
                alignment = if (upClose) Alignment.TopCenter else Alignment.BottomCenter,
            )
        }

        // Atmospheric bottom gradient blend into VN dialogue area
        if (upClose) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.35f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                NamiDeep.copy(alpha = 0.5f),
                                NamiDeep.copy(alpha = 0.95f),
                            ),
                        ),
                    ),
            )
        }
    }
}
