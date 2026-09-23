package com.orangexp.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orangexp.core.data.model.Contribution
import com.orangexp.core.designsystem.component.BreakdownRow
import com.orangexp.core.designsystem.component.EmptyState
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.designsystem.theme.OxTheme

/**
 * Where every point came from: one row per rule with the measured value, then
 * the total. The total is exactly the sum of the rows.
 */
@Composable
fun ScoreBreakdown(contributions: List<Contribution>, total: Long, modifier: Modifier = Modifier) {
    if (contributions.isEmpty()) {
        EmptyState(stringResource(R.string.breakdown_empty_title), stringResource(R.string.breakdown_empty_body), modifier)
        return
    }
    val capped = stringResource(R.string.breakdown_capped)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        contributions.forEach { c ->
            val measured = formatMetric(c.metric, c.value)
            BreakdownRow(
                label = c.label,
                points = OxFormat.signedPoints(c.points),
                detail = if (c.limited) "$measured · $capped" else measured,
            )
        }
        HorizontalDivider(color = OxTheme.colors.divider)
        BreakdownRow(label = stringResource(R.string.breakdown_total), points = OxFormat.points(total), emphasized = true)
    }
}
