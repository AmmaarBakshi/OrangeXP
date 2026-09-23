package com.orangexp.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.orangexp.core.designsystem.format.OxFormat

/**
 * A number that rolls up to its new value, like a usage meter. The final value
 * is always exact; only the transition is animated.
 */
@Composable
fun RollingCounter(
    value: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = LocalContentColor.current,
) {
    val animated = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value) {
        animated.animateTo(value.toFloat(), tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    val shown = if (animated.isRunning) animated.value.toLong() else value
    Text(text = OxFormat.points(shown), modifier = modifier, style = style, color = color, maxLines = 1)
}

/** A large counter with a small uppercase caption beneath it. */
@Composable
fun CounterBlock(
    value: Long,
    caption: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = MaterialTheme.colorScheme.onBackground,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RollingCounter(value = value, style = style, color = color)
        SectionLabel(caption)
    }
}
