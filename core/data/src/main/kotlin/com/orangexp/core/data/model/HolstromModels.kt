package com.orangexp.core.data.model

import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.core.engine.ffi.DeviceAction
import com.orangexp.core.engine.ffi.RepeatRule

enum class ReminderStatus { ACTIVE, DONE, CANCELLED }

/** A stored reminder, deadline or scheduled phone action. */
data class Reminder(
    val id: Long,
    val title: String,
    val sourceText: String,
    val kind: CommandKind,
    val atMs: Long,
    val repeat: RepeatRule,
    val nudgeMinute: Int,
    val finalAlert: Boolean,
    val action: DeviceAction?,
    val durationMinutes: Int?,
    val nextFireMs: Long?,
    val lastFiredMs: Long?,
    val status: ReminderStatus,
    val createdMs: Long,
    val completedMs: Long?,
) {
    /** Went off and is waiting to be ticked off. */
    val isDue: Boolean get() = status == ReminderStatus.ACTIVE && nextFireMs == null && kind != CommandKind.DEVICE_ACTION

    val repeats: Boolean get() = repeat == RepeatRule.DAILY || repeat == RepeatRule.WEEKLY
}

/** What a reminder alarm should say when it goes off. */
data class FiredReminder(
    val reminder: Reminder,
    /** Whole days until a deadline; 0 on the due day and for plain reminders. */
    val daysLeft: Int,
    /** Nothing further is scheduled. */
    val isFinal: Boolean,
)

enum class Speaker { USER, HOLSTROM }

data class ChatMessage(
    val id: Long,
    val speaker: Speaker,
    val text: String,
    val timestampMs: Long,
)

/** Holstrom's preferences. Times are minutes of the local day. */
data class HolstromSettings(
    /** When deadlines nudge each day. */
    val nudgeMinute: Int = 9 * 60,
    /** Used for "tomorrow" and dates without a clock time. */
    val defaultMinute: Int = 9 * 60,
    /** Used for "remind me to …" without any time. */
    val defaultDelayMinutes: Int = 60,
    /** Read answers aloud. */
    val speakReplies: Boolean = true,
    /** Run the on-device language model on the GPU. */
    val useGpu: Boolean = false,
    /** Answer free questions with the on-device model when one is installed. */
    val useModel: Boolean = true,
)
