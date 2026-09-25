package com.orangexp.feature.holstrom.brain

import android.content.Context
import android.util.Log
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.data.model.HolstromSettings
import com.orangexp.core.data.model.Reminder
import com.orangexp.core.data.model.Speaker
import com.orangexp.core.data.repository.BriefingRepository
import com.orangexp.core.data.repository.ConversationRepository
import com.orangexp.core.data.repository.HolstromSettingsRepository
import com.orangexp.core.data.repository.ReminderRepository
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.core.engine.ffi.DeviceAction
import com.orangexp.core.engine.ffi.ParsedCommand
import com.orangexp.core.engine.ffi.QueryTopic
import com.orangexp.core.engine.ffi.RepeatRule
import com.orangexp.core.llm.ChatPrompt
import com.orangexp.core.llm.LanguageModel
import com.orangexp.core.llm.Role
import com.orangexp.core.llm.Turn
import com.orangexp.core.voice.VoiceOutput
import com.orangexp.core.work.holstrom.ActionResult
import com.orangexp.core.work.holstrom.DeviceActions
import com.orangexp.core.work.holstrom.HolstromNotifier
import com.orangexp.feature.holstrom.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

enum class FollowUp { POLICY_ACCESS, INSTALL_MODEL }

/** Holstrom's reply as it forms: [thinking] until the first words, [done] at the end. */
data class HolstromReply(
    val text: String,
    val done: Boolean,
    val thinking: Boolean = false,
    val followUp: FollowUp? = null,
)

/**
 * UIH32, the part of Holstrom that decides what a sentence means and acts on it.
 *
 * Every sentence goes through the engine's deterministic parser first:
 * reminders, deadlines and phone actions are carried out directly, questions
 * about the day are answered from the briefing. Only open conversation and
 * analysis reach the on-device language model, which sees the same briefing.
 */
@Singleton
class HolstromBrain @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminders: ReminderRepository,
    private val conversation: ConversationRepository,
    private val briefing: BriefingRepository,
    private val settings: HolstromSettingsRepository,
    private val model: LanguageModel,
    private val voice: VoiceOutput,
    private val actions: DeviceActions,
    private val notifier: HolstromNotifier,
    private val answers: HolstromAnswers,
    private val prompt: HolstromPrompt,
    private val format: HolstromFormat,
    private val time: TimeSource,
) {
    /**
     * Handles one typed or spoken sentence. The conversation is stored as it
     * goes, and replies are spoken when the user has that switched on.
     *
     * @param foreground an activity is visible, so the clock app may be opened for alarms and timers.
     */
    fun handle(text: String, foreground: Boolean = true): Flow<HolstromReply> = flow {
        val input = text.trim()
        if (input.isEmpty()) return@flow
        val prefs = settings.current()
        conversation.add(Speaker.USER, input)
        val command = reminders.parse(input)
        when (command.kind) {
            CommandKind.REMINDER, CommandKind.DEADLINE -> {
                val stored = reminders.create(command, input)
                finish(HolstromReply(confirm(stored, command, prefs), done = true), prefs)
            }
            CommandKind.DEVICE_ACTION -> finish(act(command, input, foreground), prefs)
            CommandKind.QUESTION -> answer(command.topic ?: QueryTopic.OTHER, input, prefs)
        }
    }

    /** Stops speaking and any answer being generated. */
    fun interrupt() {
        voice.stop()
        model.cancel()
    }

    private suspend fun FlowCollector<HolstromReply>.finish(reply: HolstromReply, prefs: HolstromSettings) {
        conversation.add(Speaker.HOLSTROM, reply.text)
        if (prefs.speakReplies) voice.say(reply.text)
        emit(reply)
    }

    // ---- reminders ---------------------------------------------------------------

    private fun confirm(r: Reminder, command: ParsedCommand, prefs: HolstromSettings): String {
        val task = format.task(r.title)
        val first = r.nextFireMs ?: r.atMs
        return when {
            r.kind == CommandKind.DEADLINE -> context.getString(
                if (r.finalAlert) R.string.reply_deadline_timed else R.string.reply_deadline,
                r.title,
                if (r.finalAlert) format.moment(r.atMs) else format.day(r.atMs),
                format.clockOfMinute(prefs.nudgeMinute),
            )
            r.repeat == RepeatRule.DAILY -> context.getString(R.string.reply_daily, task, format.clock(first))
            r.repeat == RepeatRule.WEEKLY -> context.getString(R.string.reply_weekly, task, format.clock(first), format.weekday(first))
            !command.timeExplicit && !command.dateExplicit ->
                context.getString(R.string.reply_reminder_guessed, task, format.moment(first))
            else -> context.getString(R.string.reply_reminder, task, format.moment(first))
        }
    }

    // ---- phone actions -------------------------------------------------------------

    private suspend fun act(command: ParsedCommand, input: String, foreground: Boolean): HolstromReply {
        val action = command.action ?: return HolstromReply(context.getString(R.string.speech_other), done = true)
        val now = time.nowMs()
        val at = command.atMs ?: now
        val label = notifier.label(action)

        if (action == DeviceAction.SET_TIMER && command.durationMinutes == null) {
            return HolstromReply(context.getString(R.string.reply_timer_unknown), done = true)
        }
        if (action == DeviceAction.SET_ALARM && !command.timeExplicit) {
            return HolstromReply(context.getString(R.string.reply_alarm_unknown), done = true)
        }

        val needsPolicy = action in POLICY_ACTIONS && !actions.hasPolicyAccess()
        val immediate = at <= now + IMMEDIATE_WINDOW_MS || action == DeviceAction.SET_ALARM || action == DeviceAction.SET_TIMER
        val parts = mutableListOf<String>()
        var followUp: FollowUp? = null

        if (immediate) {
            when (actions.perform(action, at, command.durationMinutes?.toInt(), foreground)) {
                ActionResult.DONE -> parts += doneText(action, command, at)
                ActionResult.NEEDS_POLICY_ACCESS -> {
                    parts += context.getString(R.string.reply_action_needs_access, label)
                    followUp = FollowUp.POLICY_ACCESS
                }
                ActionResult.UNSUPPORTED -> parts += context.getString(R.string.reply_action_unsupported, label)
                ActionResult.NEEDS_FOREGROUND, ActionResult.FAILED -> parts += context.getString(R.string.reply_action_failed, label)
            }
        } else {
            reminders.scheduleAction(action, at, input, command.durationMinutes?.toInt())
            parts += if (needsPolicy) {
                followUp = FollowUp.POLICY_ACCESS
                context.getString(R.string.reply_action_needs_access_scheduled, label)
            } else {
                context.getString(R.string.reply_action_scheduled, label, format.moment(at).replaceFirstChar { it.uppercase() })
            }
        }

        val revertAt = command.revertAtMs
        val revert = revertFor(action)
        if (revertAt != null && revert != null && followUp == null) {
            reminders.scheduleAction(revert, revertAt, input)
            parts += context.getString(R.string.reply_action_revert, format.moment(revertAt))
        }
        return HolstromReply(parts.joinToString(" "), done = true, followUp = followUp)
    }

    private fun doneText(action: DeviceAction, command: ParsedCommand, at: Long): String = when (action) {
        DeviceAction.SILENT -> context.getString(R.string.reply_silent)
        DeviceAction.VIBRATE -> context.getString(R.string.reply_vibrate)
        DeviceAction.RING -> context.getString(R.string.reply_ring)
        DeviceAction.DND_ON -> context.getString(R.string.reply_dnd_on)
        DeviceAction.DND_OFF -> context.getString(R.string.reply_dnd_off)
        DeviceAction.FLASHLIGHT_ON -> context.getString(R.string.reply_flashlight_on)
        DeviceAction.FLASHLIGHT_OFF -> context.getString(R.string.reply_flashlight_off)
        DeviceAction.SET_ALARM -> context.getString(R.string.reply_alarm, format.moment(at))
        DeviceAction.SET_TIMER -> context.getString(R.string.reply_timer, OxFormat.duration((command.durationMinutes ?: 0u).toLong()))
    }

    private fun revertFor(action: DeviceAction): DeviceAction? = when (action) {
        DeviceAction.SILENT, DeviceAction.VIBRATE -> DeviceAction.RING
        DeviceAction.DND_ON -> DeviceAction.DND_OFF
        DeviceAction.FLASHLIGHT_ON -> DeviceAction.FLASHLIGHT_OFF
        else -> null
    }

    // ---- questions -------------------------------------------------------------------

    private suspend fun FlowCollector<HolstromReply>.answer(topic: QueryTopic, input: String, prefs: HolstromSettings) {
        val facts = briefing.current()
        val open = topic == QueryTopic.OTHER || topic == QueryTopic.SUMMARY
        val canUseModel = prefs.useModel && model.installed() != null
        if (!open || !canUseModel) {
            val text = if (topic == QueryTopic.OTHER && isGreeting(input)) {
                context.getString(R.string.answer_greeting)
            } else {
                answers.answer(topic, facts)
            }
            val followUp = if (topic == QueryTopic.OTHER && !isGreeting(input) && model.installed() == null) FollowUp.INSTALL_MODEL else null
            finish(HolstromReply(text, done = true, followUp = followUp), prefs)
            return
        }

        // Earlier turns, without the message just stored.
        val history = conversation.recent(HISTORY_TURNS + 1).dropLast(1).map {
            Turn(if (it.speaker == Speaker.USER) Role.USER else Role.MODEL, it.text.take(MAX_TURN_CHARS))
        }
        val messageId = conversation.add(Speaker.HOLSTROM, "")
        emit(HolstromReply("", done = false, thinking = true))
        if (prefs.speakReplies) voice.stop()

        val chunker = SentenceChunker()
        val text = StringBuilder()
        var lastSaved = 0L
        try {
            model.generate(ChatPrompt(prompt.system(facts), history, input), prefs.useGpu).collect { delta ->
                text.append(delta)
                if (prefs.speakReplies) chunker.add(delta).forEach(voice::enqueue)
                val now = System.currentTimeMillis()
                if (now - lastSaved > SAVE_EVERY_MS) {
                    lastSaved = now
                    conversation.update(messageId, text.toString().trim())
                }
                emit(HolstromReply(text.toString().trim(), done = false))
            }
            if (prefs.speakReplies) chunker.rest().takeIf { it.isNotEmpty() }?.let(voice::enqueue)
            val final = text.toString().trim().ifEmpty { answers.answer(topic, facts) }
            conversation.update(messageId, final)
            emit(HolstromReply(final, done = true))
        } catch (e: kotlinx.coroutines.CancellationException) {
            conversation.update(messageId, text.toString().trim())
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Language model failed", e)
            val fallback = context.getString(R.string.answer_model_failed, e.message ?: e.javaClass.simpleName) +
                " " + (if (topic == QueryTopic.SUMMARY) answers.summary(facts) else answers.answer(QueryTopic.OTHER, facts))
            conversation.update(messageId, fallback)
            if (prefs.speakReplies) voice.say(fallback)
            emit(HolstromReply(fallback, done = true))
        }
    }

    private fun isGreeting(input: String): Boolean =
        input.lowercase().trim().trimEnd('!', '.', '?') in GREETINGS

    private companion object {
        const val TAG = "HolstromBrain"
        const val IMMEDIATE_WINDOW_MS = 30_000L
        const val HISTORY_TURNS = 8
        const val MAX_TURN_CHARS = 800
        const val SAVE_EVERY_MS = 400L
        val POLICY_ACTIONS = setOf(DeviceAction.SILENT, DeviceAction.VIBRATE, DeviceAction.DND_ON, DeviceAction.DND_OFF)
        val GREETINGS = setOf("hi", "hello", "hey", "hey holstrom", "hi holstrom", "hello holstrom", "yo", "good morning", "good evening", "thanks", "thank you")
    }
}
