package com.orangexp.core.database.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Something Holstrom has to do later: a reminder, a deadline with daily nudges,
 * or a phone action such as silencing the ringer. Enum columns hold engine names.
 */
@Entity(tableName = "reminders", indices = [Index("status"), Index("nextFireMs")])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** The sentence as typed or spoken. */
    val sourceText: String,
    /** Engine `CommandKind` name: REMINDER, DEADLINE or DEVICE_ACTION. */
    val kind: String,
    /** Reminder time, deadline, or when to act. */
    val atMs: Long,
    /** Engine `RepeatRule` name. */
    val repeat: String = "NONE",
    val nudgeMinute: Int = 9 * 60,
    val finalAlert: Boolean = false,
    /** Engine `DeviceAction` name for phone actions. */
    val action: String? = null,
    val durationMinutes: Int? = null,
    /** Next scheduled alarm; `null` once nothing is left to fire. */
    val nextFireMs: Long? = null,
    val lastFiredMs: Long? = null,
    /** ACTIVE, DONE or CANCELLED. */
    val status: String = "ACTIVE",
    val createdMs: Long,
    val completedMs: Long? = null,
)

/**
 * A reminder ticked off as done. Repeating reminders are completed once per
 * occurrence, so completions are counted here rather than on the reminder.
 */
@Entity(
    tableName = "reminder_completions",
    indices = [Index("completedMs"), Index("reminderId")],
    foreignKeys = [
        ForeignKey(entity = ReminderEntity::class, parentColumns = ["id"], childColumns = ["reminderId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class ReminderCompletionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long?,
    val completedMs: Long,
)

/** One line of the conversation with Holstrom. */
@Entity(tableName = "assistant_messages", indices = [Index("timestampMs")])
data class AssistantMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** USER or ASSISTANT. */
    val role: String,
    val text: String,
    val timestampMs: Long,
)
