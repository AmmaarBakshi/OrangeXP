package com.orangexp.feature.holstrom.brain

import android.content.Context
import com.orangexp.core.common.time.epochDayOf
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.data.model.Reminder
import com.orangexp.core.data.repository.Briefing
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.core.engine.ffi.CompetitionVerdict
import com.orangexp.core.engine.ffi.QueryTopic
import com.orangexp.core.ui.formatFull
import com.orangexp.core.ui.formatMedium
import com.orangexp.core.ui.labelRes
import com.orangexp.feature.holstrom.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Answers about your day straight from the briefing: instant, exact and
 * available without a language model.
 */
@Singleton
class HolstromAnswers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val format: HolstromFormat,
    private val time: TimeSource,
) {
    fun answer(topic: QueryTopic, b: Briefing): String = when (topic) {
        QueryTopic.TIME -> s(R.string.answer_time, format.clock(b.nowMs))
        QueryTopic.DATE -> s(R.string.answer_date, b.today.formatFull())
        QueryTopic.SCORE -> score(b)
        QueryTopic.SLEEP -> sleep(b)
        QueryTopic.STEPS -> steps(b)
        QueryTopic.STREAK -> streak(b)
        QueryTopic.CLASSES -> classes(b)
        QueryTopic.STUDY -> study(b)
        QueryTopic.REMINDERS -> reminders(b)
        QueryTopic.COMPETITIONS -> competitions(b)
        QueryTopic.SUMMARY -> summary(b)
        QueryTopic.OTHER -> s(R.string.answer_no_model)
    }

    fun summary(b: Briefing): String = listOfNotNull(
        score(b),
        if (b.sleepMinutes != null) sleep(b) else null,
        if (b.steps != null) steps(b) else null,
        streak(b),
        trend(b),
        nextThing(b),
        if (b.tasksCompleted > 0) s(R.string.answer_summary_tasks, b.tasksCompleted) else null,
        reminders(b),
    ).joinToString(" ")

    private fun score(b: Briefing): String {
        val points = b.points ?: return s(R.string.answer_score_none)
        val state = b.state?.let { context.getString(it.labelRes).lowercase() }.orEmpty()
        val parts = mutableListOf(s(R.string.answer_score, OxFormat.points(points), state))
        b.stateReason?.let { parts += s(R.string.answer_score_reason, state, it.lowercase()) }
        val top = b.contributions.filter { it.points > 0 }.take(2)
        if (top.isNotEmpty()) {
            parts += s(R.string.answer_score_top, top.joinToString(", ") { "${it.label.lowercase()} ${OxFormat.signedPoints(it.points)}" })
        }
        return parts.joinToString(" ")
    }

    private fun sleep(b: Briefing): String {
        val minutes = b.sleepMinutes ?: return s(R.string.answer_sleep_none)
        val first = b.sleepConfidence?.let { s(R.string.answer_sleep, OxFormat.duration(minutes.toLong()), it) }
            ?: s(R.string.answer_sleep_plain, OxFormat.duration(minutes.toLong()))
        val second = when {
            b.sleepingSinceMs != null -> s(R.string.answer_sleep_asleep, format.clock(b.sleepingSinceMs!!))
            b.awakeSinceMs != null -> s(R.string.answer_sleep_awake, format.clock(b.awakeSinceMs!!))
            else -> null
        }
        return listOfNotNull(first, second).joinToString(" ")
    }

    private fun steps(b: Briefing): String {
        val steps = b.steps ?: return s(R.string.answer_steps_none)
        val count = OxFormat.points(steps)
        return b.walkingMeters?.takeIf { it > 0 }?.let { s(R.string.answer_steps, count, OxFormat.distance(it)) }
            ?: s(R.string.answer_steps_plain, count)
    }

    private fun streak(b: Briefing): String =
        if (b.streak > 0) s(R.string.answer_streak, b.streak, b.longestStreak) else s(R.string.answer_streak_zero, b.longestStreak)

    private fun trend(b: Briefing): String? {
        if (b.average7 <= 0.0) return null
        val average = OxFormat.points(b.average7.roundToLong())
        return when {
            b.trendPerDay30 > TREND_EPSILON -> s(R.string.answer_summary_trend_up, average)
            b.trendPerDay30 < -TREND_EPSILON -> s(R.string.answer_summary_trend_down, average)
            else -> s(R.string.answer_summary_trend_flat, average)
        }
    }

    private fun nextThing(b: Briefing): String? = b.nextClass?.let {
        s(R.string.answer_classes_next, it.title, format.clockOfMinute(it.startMinute))
    }

    private fun classes(b: Briefing): String {
        if (b.classesToday.isEmpty()) return s(R.string.answer_classes_none)
        val list = b.classesToday.joinToString(", ") {
            "${it.title} ${format.clockOfMinute(it.startMinute)}–${format.clockOfMinute(it.endMinute)}"
        }
        val next = b.nextClass?.let { s(R.string.answer_classes_next, it.title, format.clockOfMinute(it.startMinute)) }
            ?: s(R.string.answer_classes_done)
        val leave = if (b.leaveAtMs != null && b.startPreparingAtMs != null && b.leaveAtMs!! > b.nowMs) {
            s(R.string.answer_leave, format.clock(b.leaveAtMs!!), format.clock(b.startPreparingAtMs!!))
        } else {
            null
        }
        return listOfNotNull(s(R.string.answer_classes, list), next, leave).joinToString(" ")
    }

    private fun study(b: Briefing): String {
        val parts = mutableListOf<String>()
        val topics = b.studyPlanToday.distinctBy { it.topicId }
        if (topics.isEmpty()) {
            parts += s(R.string.answer_study_none)
        } else {
            val list = b.studyPlanToday.joinToString(", ") {
                "${it.topicTitle} (${it.subjectName}, ${OxFormat.duration(it.minutes.toLong())})"
            }
            parts += s(R.string.answer_study, list, topics.count { it.topicCompleted }, topics.size)
        }
        if (b.subjectsAtRisk.isNotEmpty()) {
            parts += s(R.string.answer_study_risk, b.subjectsAtRisk.joinToString(", ") { it.subjectName })
        }
        b.upcomingExams.firstOrNull()?.let { (name, day) -> parts += s(R.string.answer_study_exam, name, day.formatMedium()) }
        return parts.joinToString(" ")
    }

    private fun reminders(b: Briefing): String {
        val pending = b.reminders.filter { it.kind != CommandKind.DEVICE_ACTION }
        if (pending.isEmpty()) return s(R.string.answer_reminders_none)
        return s(R.string.answer_reminders, pending.take(MAX_LISTED).joinToString("; ") { describe(it) })
    }

    /** "Texting ria, tomorrow at 09:00" / "Submit my assignment, due 3 Oct". */
    fun describe(r: Reminder): String = when {
        r.kind == CommandKind.DEADLINE -> "${r.title}, ${s(R.string.plans_due_on, format.day(r.atMs))}"
        r.isDue -> r.title
        else -> "${r.title}, ${format.moment(r.nextFireMs ?: r.atMs)}"
    }

    private fun competitions(b: Briefing): String {
        if (b.upcomingCompetitions.isEmpty()) return s(R.string.answer_competitions_none)
        val list = b.upcomingCompetitions.take(MAX_LISTED).joinToString("; ") { (c, a) ->
            val verdict = when (a?.verdict) {
                CompetitionVerdict.RECOMMENDED -> s(R.string.verdict_recommended)
                CompetitionVerdict.COMMITTED -> s(R.string.verdict_committed)
                null -> null
                else -> s(R.string.verdict_tight)
            }
            listOfNotNull(c.name, c.eventStartDay.formatMedium(), verdict).joinToString(", ")
        }
        return s(R.string.answer_competitions, list, b.competitionCapacity ?: 0)
    }

    /** Whole days until [ms], for deadline wording. */
    fun daysUntil(ms: Long): Int = epochDayOf(ms, time.zone()) - epochDayOf(time.now().toEpochMilli(), time.zone())

    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    private companion object {
        const val TREND_EPSILON = 25.0
        const val MAX_LISTED = 5
    }
}
