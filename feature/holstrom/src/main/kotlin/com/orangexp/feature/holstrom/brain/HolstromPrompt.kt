package com.orangexp.feature.holstrom.brain

import com.orangexp.core.data.repository.Briefing
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.core.ui.formatFull
import com.orangexp.core.ui.formatMedium
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * The language model's instructions and everything it may say about the user.
 * The facts are plain lines from the briefing, so the model can reason over
 * them without being able to change anything.
 */
@Singleton
class HolstromPrompt @Inject constructor(
    private val format: HolstromFormat,
    private val answers: HolstromAnswers,
) {
    fun system(b: Briefing): String = buildString {
        appendLine(
            "You are Holstrom (internal name UIH32), the personal assistant inside OrangeXP, an app that " +
                "measures the user's day (sleep, steps, study, classes, discipline) and turns it into a daily score. " +
                "You run entirely offline on the user's phone.",
        )
        appendLine(
            "Style: calm, direct and warm, with a little dry wit, like a capable butler. Reply in at most four short " +
                "sentences unless asked for more. Plain text only, no markdown, no lists unless asked. " +
                "Your replies may be read aloud.",
        )
        appendLine(
            "Use only the facts below about the user. Never invent numbers, times or events; if something is not in " +
                "the facts, say you don't know. When asked for analysis, compare today with the averages and trend, " +
                "point out what is going well and the single most useful thing to improve.",
        )
        appendLine(
            "Reminders, deadlines and phone actions are handled by the app itself before you are asked. If the user " +
                "wants one, tell them to say it directly, for example \"remind me to call mom at 6pm\".",
        )
        appendLine()
        appendLine("FACTS")
        facts(b).forEach { appendLine("- $it") }
    }

    fun facts(b: Briefing): List<String> = buildList {
        add("Now: ${b.today.formatFull()}, ${format.clock(b.nowMs)}")
        if (b.points != null) {
            add("Score today: ${OxFormat.points(b.points!!)} points, day state ${b.state?.name?.lowercase()}" + (b.stateReason?.let { " because of $it" } ?: ""))
            b.contributions.take(6).forEach { add("Score part: ${it.label} ${OxFormat.signedPoints(it.points)}") }
        } else {
            add("Score today: not computed yet")
        }
        b.sleepMinutes?.let { add("Last sleep: ${OxFormat.duration(it.toLong())}" + (b.sleepConfidence?.let { c -> " (confidence $c%)" } ?: "")) }
        b.awakeSinceMs?.let { add("Awake since ${format.clock(it)}") }
        b.sleepingSinceMs?.let { add("Resting since ${format.clock(it)}") }
        b.steps?.let { add("Steps today: $it" + (b.walkingMeters?.let { m -> ", walked ${OxFormat.distance(m)}" } ?: "")) }
        b.studyMinutes?.let { add("Studied today: ${OxFormat.duration(it.roundToLong())}") }
        b.screenMinutes?.let { add("Screen time today: ${OxFormat.duration(it.roundToLong())}") }
        add("Tasks ticked off today: ${b.tasksCompleted}")
        add("Streak: ${b.streak} days (longest ${b.longestStreak})")
        if (b.lastDays.isNotEmpty()) {
            add("Last 7 days points: " + b.lastDays.joinToString(", ") { (day, points) -> "${day.formatMedium()} ${OxFormat.points(points)}" })
        }
        add("Averages: 7-day ${OxFormat.points(b.average7.roundToLong())}, 30-day ${OxFormat.points(b.average30.roundToLong())}; trend ${"%+.0f".format(b.trendPerDay30)} points/day; active on ${b.consistency30.roundToLong()}% of the last 30 days")
        add("Lifetime points: ${OxFormat.points(b.lifetimePoints)}")
        b.bestDay?.let { (day, points) -> add("Best day: ${day.formatMedium()} with ${OxFormat.points(points)}") }
        if (b.classesToday.isEmpty()) {
            add("No classes today")
        } else {
            b.classesToday.forEach {
                add("Class today: ${it.title} ${format.clockOfMinute(it.startMinute)}-${format.clockOfMinute(it.endMinute)}" + (it.location.takeIf { l -> l.isNotBlank() }?.let { l -> " in $l" } ?: ""))
            }
        }
        if (b.leaveAtMs != null && b.leaveAtMs!! > b.nowMs) add("Leave home by ${format.clock(b.leaveAtMs!!)}")
        b.studyPlanToday.forEach {
            add("Study plan today: ${it.topicTitle} (${it.subjectName}), ${it.minutes} min" + if (it.topicCompleted) ", done" else "")
        }
        b.subjectsAtRisk.forEach { add("Subject behind schedule: ${it.subjectName}, ${it.shortfallMinutes} min short") }
        b.upcomingExams.take(4).forEach { (name, day) -> add("Exam: $name on ${day.formatMedium()}") }
        b.upcomingCompetitions.take(4).forEach { (c, a) ->
            add("Competition: ${c.name} on ${c.eventStartDay.formatMedium()}" + (a?.let { ", ${it.verdict.name.lowercase().replace('_', ' ')}" } ?: ""))
        }
        b.reminders.filter { it.kind != CommandKind.DEVICE_ACTION }.take(8).forEach { add("Pending: ${answers.describe(it)}") }
    }
}
