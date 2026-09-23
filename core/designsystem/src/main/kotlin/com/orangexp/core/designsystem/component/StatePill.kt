package com.orangexp.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.orangexp.core.designsystem.theme.OxTheme

/** The four algorithmic day states, independent of the engine's type. */
enum class StateTone { Good, Moderate, Bad, Critical }

@Composable
fun StateTone.color(): Color = when (this) {
    StateTone.Good -> OxTheme.colors.stateGood
    StateTone.Moderate -> OxTheme.colors.stateModerate
    StateTone.Bad -> OxTheme.colors.stateBad
    StateTone.Critical -> OxTheme.colors.stateCritical
}

@Composable
fun StatePill(tone: StateTone, label: String, modifier: Modifier = Modifier) {
    val color = tone.color()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = if (tone == StateTone.Critical) 1f else 0.16f),
        border = BorderStroke(1.dp, if (tone == StateTone.Critical) OxTheme.colors.stateCriticalOutline else color),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dot(color = color, outline = if (tone == StateTone.Critical) OxTheme.colors.stateCriticalOutline else null)
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = if (tone == StateTone.Critical) Color.White else color,
            )
        }
    }
}
