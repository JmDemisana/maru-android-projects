package com.maru.namispace.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.ui.theme.MoodHappy
import com.maru.namispace.ui.theme.NamiAccent
import com.maru.namispace.ui.theme.NamiBlush
import kotlinx.coroutines.delay
import kotlin.random.Random

data class Particle(
    val id: Long = System.nanoTime() + Random.nextLong(1000),
    val text: String,
    val color: Color,
    val startXOffsetDp: Float,
    val durationMs: Int = 1200,
)

@Composable
fun ParticleOverlay(
    particles: List<Particle>,
    onParticleFinished: (Particle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        particles.forEach { particle ->
            key(particle.id) {
                SingleParticle(
                    particle = particle,
                    onFinished = { onParticleFinished(particle) },
                )
            }
        }
    }
}

@Composable
private fun SingleParticle(
    particle: Particle,
    onFinished: () -> Unit,
) {
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(particle.id) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(particle.durationMs, easing = LinearOutSlowInEasing),
        )
        onFinished()
    }

    val progress = animProgress.value
    val translateY = -progress * 220f
    val alpha = (1f - progress * progress).coerceIn(0f, 1f)
    val scale = 0.8f + (progress * 0.6f)

    Text(
        text = particle.text,
        color = particle.color.copy(alpha = alpha),
        fontSize = 24.sp,
        modifier = Modifier
            .offset(x = particle.startXOffsetDp.dp)
            .graphicsLayer {
                this.translationY = translateY
                this.scaleX = scale
                this.scaleY = scale
                this.alpha = alpha
            },
    )
}

/** Helper to spawn particles */
fun createHeartParticles(count: Int = 4): List<Particle> {
    val icons = listOf("♡", "♥", "✨", "🌸")
    val colors = listOf(NamiBlush, NamiAccent, MoodHappy)
    return List(count) {
        Particle(
            text = icons.random(),
            color = colors.random(),
            startXOffsetDp = Random.nextInt(-90, 90).toFloat(),
            durationMs = Random.nextInt(1000, 1400),
        )
    }
}

fun createStarParticles(count: Int = 4): List<Particle> {
    val icons = listOf("★", "✨", "✦")
    return List(count) {
        Particle(
            text = icons.random(),
            color = MoodHappy,
            startXOffsetDp = Random.nextInt(-100, 100).toFloat(),
            durationMs = Random.nextInt(1100, 1500),
        )
    }
}
