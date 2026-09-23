package com.orangexp.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.orangexp.core.designsystem.component.StateTone
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.engine.ffi.Category
import com.orangexp.core.engine.ffi.DayState

fun DayState.tone(): StateTone = when (this) {
    DayState.GREEN -> StateTone.Good
    DayState.ORANGE -> StateTone.Moderate
    DayState.RED -> StateTone.Bad
    DayState.BLACK -> StateTone.Critical
}

@get:StringRes
val DayState.labelRes: Int
    get() = when (this) {
        DayState.GREEN -> R.string.state_green
        DayState.ORANGE -> R.string.state_orange
        DayState.RED -> R.string.state_red
        DayState.BLACK -> R.string.state_black
    }

@Composable
fun DayState.label(): String = stringResource(labelRes)

@get:StringRes
val Category.labelRes: Int
    get() = when (this) {
        Category.SLEEP -> R.string.category_sleep
        Category.WAKEFULNESS -> R.string.category_wakefulness
        Category.STUDY -> R.string.category_study
        Category.SYLLABUS -> R.string.category_syllabus
        Category.ATTENDANCE -> R.string.category_attendance
        Category.WALKING -> R.string.category_walking
        Category.PHYSICAL_ACTIVITY -> R.string.category_physical_activity
        Category.SCHEDULE -> R.string.category_schedule
        Category.TRAVEL -> R.string.category_travel
        Category.TASK_COMPLETION -> R.string.category_task_completion
        Category.PHONE_USAGE -> R.string.category_phone_usage
    }

/** Human-readable measured value for a metric key, e.g. "6h 21m", "4.7 km", "92%". */
fun formatMetric(metric: String, value: Double): String = when (metric) {
    MetricKeys.SLEEP_MINUTES, MetricKeys.AWAKE_MINUTES, MetricKeys.STUDY_MINUTES,
    MetricKeys.ACTIVE_MINUTES, MetricKeys.SCREEN_MINUTES, MetricKeys.SYLLABUS_COMPLETED_MINUTES,
    -> OxFormat.duration(value.toLong())
    MetricKeys.WALKING_METERS -> OxFormat.distance(value)
    MetricKeys.ATTENDANCE_PERCENT, MetricKeys.SCHEDULE_ADHERENCE_PERCENT -> OxFormat.percent(value)
    MetricKeys.STEPS -> OxFormat.points(value.toLong())
    else -> if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)
}
