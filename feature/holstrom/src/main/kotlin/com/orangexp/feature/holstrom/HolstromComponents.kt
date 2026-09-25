package com.orangexp.feature.holstrom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Holstrom's presence: an ember core that breathes when idle, swells with your
 * voice while listening, circles while thinking and pulses while speaking.
 */
@Composable
fun HolstromOrb(state: OrbState, level: Float, modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val glow = MaterialTheme.colorScheme.primaryContainer
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (state == OrbState.SPEAKING) 700 else 2_800, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "spin",
    )
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        label = "ripple",
    )
    val voice by animateFloatAsState(if (state == OrbState.LISTENING) level else 0f, tween(120), label = "level")

    Canvas(modifier.size(size)) {
        val radius = min(this.size.width, this.size.height) / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val scale = when (state) {
            OrbState.IDLE -> 0.54f + 0.03f * breath
            OrbState.LISTENING -> 0.56f + 0.16f * voice
            OrbState.THINKING -> 0.52f
            OrbState.SPEAKING -> 0.55f + 0.07f * breath
        }
        // Halo
        drawCircle(
            brush = Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0f)), center, radius),
            radius = radius,
            center = center,
        )
        if (state == OrbState.LISTENING) {
            for (k in 0..1) {
                val t = (ripple + k * 0.5f) % 1f
                drawCircle(
                    color = primary.copy(alpha = (1f - t) * 0.5f),
                    radius = radius * (scale + (0.95f - scale) * t),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        if (state == OrbState.THINKING) {
            val r = radius * 0.8f
            drawArc(
                color = primary,
                startAngle = spin,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
        // Core
        drawCircle(
            brush = Brush.radialGradient(
                listOf(primary.copy(alpha = 1f), primary.copy(alpha = 0.75f), primary.copy(alpha = 0.35f)),
                center,
                radius * scale,
            ),
            radius = radius * scale,
            center = center,
        )
        drawCircle(
            color = glow.copy(alpha = 0.35f + 0.2f * breath),
            radius = radius * scale * 0.35f,
            center = Offset(center.x - radius * scale * 0.25f, center.y - radius * scale * 0.25f),
        )
    }
}

/**
 * Tap to talk (ends by itself after a pause), or hold to talk and release to
 * finish. Tapping while listening stops.
 */
@Composable
fun MicButton(
    listening: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val isListening by rememberUpdatedState(listening)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val label = stringResource(if (listening) R.string.talk_stop else R.string.talk_mic)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (isListening) {
                            stop()
                            return@detectTapGestures
                        }
                        val pressedAt = System.currentTimeMillis()
                        start()
                        tryAwaitRelease()
                        if (System.currentTimeMillis() - pressedAt > HOLD_THRESHOLD_MS) stop()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = null,
            tint = if (listening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private const val HOLD_THRESHOLD_MS = 400L
