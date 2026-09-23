package com.orangexp.feature.widgets

import android.content.Context
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.dayOfWeek
import com.orangexp.core.common.time.today
import com.orangexp.core.data.repository.AcademicRepository
import com.orangexp.core.data.repository.DayRepository
import com.orangexp.core.data.repository.TravelRepository
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.engine.ffi.XpPool
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalTime

/** Widgets are created by the system, not Hilt, so they reach repositories through an entry point. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun days(): DayRepository
    fun academics(): AcademicRepository
    fun travel(): TravelRepository
    fun time(): TimeSource
}

internal data class ScoreData(
    val day: EpochDay,
    val points: Long,
    val state: DayState,
    val streak: Int,
    val sleepMinutes: Int?,
    val distanceMeters: Double?,
    val studyMinutes: Int?,
)

internal data class CalendarCell(val week: Int, val row: Int, val level: Int, val isToday: Boolean)

internal data class StreakData(
    val current: Int,
    val longest: Int,
    val todayQualifies: Boolean,
    val cells: List<CalendarCell>,
    val weekCount: Int,
)

internal data class ClassRow(val startMinute: Int, val endMinute: Int, val title: String, val location: String)

internal data class TimetableData(
    val day: EpochDay,
    val isToday: Boolean,
    val nowMinute: Int,
    val classes: List<ClassRow>,
    val leaveMinute: Int?,
)

internal data class StatsData(
    val lifetime: Long,
    val pools: Map<XpPool, Long>,
    val average7: Long,
    val consistency: Int,
    val bestDay: Long,
    val daysTracked: Int,
)

/** Reads the stored, engine-evaluated data each widget shows. Never computes scores itself. */
internal class WidgetDataLoader(context: Context) {
    private val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

    suspend fun score(): ScoreData {
        val today = entry.time().today()
        val breakdown = entry.days().breakdown(today).first()
        val streak = entry.days().history(heatmapDays = 7).first().streak
        val metrics = breakdown?.metrics.orEmpty()
        return ScoreData(
            day = today,
            points = breakdown?.totalPoints ?: 0,
            state = breakdown?.state ?: DayState.GREEN,
            streak = streak.current.toInt(),
            sleepMinutes = metrics[MetricKeys.SLEEP_MINUTES]?.toInt(),
            distanceMeters = metrics[MetricKeys.WALKING_METERS],
            studyMinutes = metrics[MetricKeys.STUDY_MINUTES]?.toInt(),
        )
    }

    suspend fun streak(): StreakData {
        val history = entry.days().history(heatmapDays = CALENDAR_DAYS).first()
        return StreakData(
            current = history.streak.current.toInt(),
            longest = history.streak.longest.toInt(),
            todayQualifies = history.streak.todayQualifies,
            cells = history.heatmap.cells.map {
                CalendarCell(it.weekIndex.toInt(), it.row.toInt(), it.level.toInt(), it.isToday)
            },
            weekCount = history.heatmap.weekCount.toInt(),
        )
    }

    /** Today's classes, or the next day with classes once today's are over. */
    suspend fun timetable(): TimetableData {
        val time = entry.time()
        val today = time.today()
        val now = LocalTime.ofInstant(time.now(), time.zone()).let { it.hour * 60 + it.minute }
        val entries = entry.academics().timetable.first()
        fun classesOn(day: EpochDay) = entries
            .filter { it.weekday == day.dayOfWeek() }
            .sortedBy { it.startMinute }
            .map { ClassRow(it.startMinute, it.endMinute, it.title, it.location) }

        val todays = classesOn(today)
        val day = if (todays.any { it.endMinute > now }) {
            today
        } else {
            (1..7).map { today + it }.firstOrNull { classesOn(it).isNotEmpty() } ?: today
        }
        return TimetableData(
            day = day,
            isToday = day == today,
            nowMinute = now,
            classes = classesOn(day),
            leaveMinute = entry.travel().departure(day).first()?.plan?.leaveMinute,
        )
    }

    suspend fun stats(): StatsData {
        val history = entry.days().history(heatmapDays = 7).first()
        val stats = history.statistics
        return StatsData(
            lifetime = stats.lifetimePoints,
            pools = stats.lifetimeByPool.associate { it.pool to it.points },
            average7 = Math.round(stats.rollingAverage7),
            consistency = Math.round(stats.consistencyPercent30).toInt(),
            bestDay = stats.bestDay?.points ?: 0,
            daysTracked = stats.daysTracked.toInt(),
        )
    }

    companion object {
        /** About 26 weeks: plenty for the widest widget. */
        const val CALENDAR_DAYS = 182
    }
}
