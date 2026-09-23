package com.orangexp.core.common.widgets

/** Home-screen widgets the app offers. */
enum class HomeWidgetKind { SCORE, STREAK, TIMETABLE, STATS }

/**
 * Lets any screen offer "add to home screen" without depending on the widgets
 * module. Implemented in `feature:widgets`.
 */
interface HomeWidgets {
    /** Whether the launcher accepts pin requests (Samsung One UI and most launchers do). */
    fun canPin(): Boolean

    /** Shows the launcher's "add widget" prompt. */
    suspend fun requestPin(kind: HomeWidgetKind)
}
