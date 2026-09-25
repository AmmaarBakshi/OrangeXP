package com.orangexp.core.data.repository

import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.dayOfWeek
import com.orangexp.core.common.time.today
import com.orangexp.core.data.model.Contribution
import com.orangexp.core.data.model.Reminder
import com.orangexp.core.data.model.StudyPlanItem
import com.orangexp.core.data.model.SubjectForecast
import com.orangexp.core.data.model.TimetableEntry
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.engine.ffi.CompetitionAssessment
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.engine.ffi.SubjectStatus
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything Holstrom knows about you right now, gathered from every part of
 * the app in one read. Answers and the language model's context are built from it.
 */
data class Briefing(
    val nowMs: Long,
    val today: EpochDay,
    val minuteOfDay: Int,
    // Today
    val points: Long?,
    val state: DayState?,
    val stateReason: String?,
    val contributions: List<Contribution>,
    val sleepMinutes: Int?,
    val sleepConfidence: Int?,
    val awakeSinceMs: Long?,
    val sleepingSinceMs: Long?,
    val steps: Long?,
    val walkingMeters: Double?,
    val studyMinutes: Double?,
    val screenMinutes: Double?,
    val tasksCompleted: Int,
    // History
    val streak: Int,
    val longestStreak: Int,
    val lastDays: List<Pair<EpochDay, Long>>,
    val average7: Double,
    val average30: Double,
    val trendPerDay30: Double,
    val consistency30: Double,
    val lifetimePoints: Long,
    val bestDay: Pair<EpochDay, Long>?,
    // Academics and travel
    val classesToday: List<TimetableEntry>,
    val nextClass: TimetableEntry?,
    val leaveAtMs: Long?,
    val startPreparingAtMs: Long?,
    val studyPlanToday: List<StudyPlanItem>,
    val subjectsAtRisk: List<SubjectForecast>,
    val upcomingExams: List<Pair<String, EpochDay>>,
    // Competitions
    val upcomingCompetitions: List<Pair<CompetitionItem, CompetitionAssessment?>>,
    val competitionCapacity: Int?,
    // Holstrom
    val reminders: List<Reminder>,
)

interface BriefingRepository {
    suspend fun current(): Briefing
}

@Singleton
internal class OfflineBriefingRepository @Inject constructor(
    private val days: DayRepository,
    private val academics: AcademicRepository,
    private val travel: TravelRepository,
    private val competitions: CompetitionRepository,
    private val reminders: ReminderRepository,
    private val time: TimeSource,
) : BriefingRepository {

    override suspend fun current(): Briefing {
        val today = time.today()
        val now = time.now()
        val minute = LocalTime.ofInstant(now, time.zone()).let { it.hour * 60 + it.minute }
        val breakdown = days.breakdown(today).first()
        val history = days.history(heatmapDays = 7).first()
        val sleep = days.sleepStatus.first()
        val metrics = breakdown?.metrics.orEmpty()
        val weekday = today.dayOfWeek()
        val classes = academics.timetable.first().filter { it.weekday == weekday }.sortedBy { it.startMinute }
        val forecasts = academics.forecasts.first()
        val departure = travel.departure(today).first()
        val overview = competitions.overview.first()
        val stats = history.statistics

        return Briefing(
            nowMs = now.toEpochMilli(),
            today = today,
            minuteOfDay = minute,
            points = breakdown?.totalPoints,
            state = breakdown?.state,
            stateReason = breakdown?.let { b -> b.findings.firstOrNull { it.ruleId == b.decisiveStateRuleId }?.label },
            contributions = breakdown?.contributions.orEmpty().sortedByDescending { it.points },
            sleepMinutes = metrics[MetricKeys.SLEEP_MINUTES]?.toInt() ?: sleep.lastSleepMinutes,
            sleepConfidence = sleep.lastSleepConfidence,
            awakeSinceMs = sleep.awakeSinceMs,
            sleepingSinceMs = sleep.sleepingSinceMs,
            steps = metrics[MetricKeys.STEPS]?.toLong(),
            walkingMeters = metrics[MetricKeys.WALKING_METERS],
            studyMinutes = metrics[MetricKeys.STUDY_MINUTES],
            screenMinutes = metrics[MetricKeys.SCREEN_MINUTES],
            tasksCompleted = metrics[MetricKeys.TASKS_COMPLETED]?.toInt() ?: 0,
            streak = history.streak.current.toInt(),
            longestStreak = history.streak.longest.toInt(),
            lastDays = history.heatmap.cells.map { it.epochDay to it.points },
            average7 = stats.rollingAverage7,
            average30 = stats.rollingAverage30,
            trendPerDay30 = stats.trendPerDay30,
            consistency30 = stats.consistencyPercent30,
            lifetimePoints = stats.lifetimePoints,
            bestDay = stats.bestDay?.let { it.epochDay to it.points },
            classesToday = classes,
            nextClass = classes.firstOrNull { it.startMinute > minute },
            leaveAtMs = departure?.leaveMs,
            startPreparingAtMs = departure?.startPreparingMs,
            studyPlanToday = academics.plan(today, today).first(),
            subjectsAtRisk = forecasts.filter {
                it.status == SubjectStatus.AT_RISK || it.status == SubjectStatus.DEADLINE_PASSED
            },
            upcomingExams = academics.subjects.first()
                .mapNotNull { s -> s.examDay?.takeIf { it >= today }?.let { s.name to it } }
                .sortedBy { it.second },
            upcomingCompetitions = overview.competitions
                .filter { it.eventEndDay >= today && it.result == null }
                .map { it to overview.assessments[it.id] },
            competitionCapacity = overview.plan.maxParallel.toInt(),
            reminders = reminders.active.first(),
        )
    }
}
