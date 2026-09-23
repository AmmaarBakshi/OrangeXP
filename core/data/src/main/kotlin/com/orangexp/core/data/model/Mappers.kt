package com.orangexp.core.data.model

import com.orangexp.core.database.model.DayDetail
import com.orangexp.core.database.model.StudyWindowEntity
import com.orangexp.core.database.model.SubjectWithUnits
import com.orangexp.core.database.model.TimetableSlotEntity
import com.orangexp.core.database.model.TopicEntity
import com.orangexp.core.engine.ffi.Category
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.engine.ffi.Weekday
import java.time.DayOfWeek

internal fun DayOfWeek.toEngine(): Weekday = Weekday.entries[value - 1]

internal fun Weekday.toDayOfWeek(): DayOfWeek = DayOfWeek.of(ordinal + 1)

internal fun TimetableSlotEntity.toModel() = TimetableEntry(
    id = id,
    title = title,
    weekday = DayOfWeek.of(weekday),
    startMinute = startMinute,
    endMinute = endMinute,
    location = location,
    teacher = teacher,
    notes = notes,
    requiresTravel = requiresTravel,
    travelMinutes = travelMinutes,
    preparationMinutes = preparationMinutes,
)

internal fun TimetableEntry.toEntity() = TimetableSlotEntity(
    id = id,
    title = title.trim(),
    weekday = weekday.value,
    startMinute = startMinute,
    endMinute = endMinute,
    location = location.trim(),
    teacher = teacher.trim(),
    notes = notes.trim(),
    requiresTravel = requiresTravel,
    travelMinutes = travelMinutes,
    preparationMinutes = preparationMinutes,
)

internal fun TopicEntity.toModel() = Topic(
    id = id,
    unitId = unitId,
    subjectId = subjectId,
    title = title,
    position = position,
    estimatedMinutes = estimatedMinutes,
    difficulty = difficulty,
    completedAtMs = completedAtMs,
)

internal fun SubjectWithUnits.toModel() = Subject(
    id = subject.id,
    name = subject.name,
    examDay = subject.examEpochDay,
    priority = subject.priority,
    units = units.sortedBy { it.unit.position }.map { unitWithTopics ->
        SyllabusUnit(
            id = unitWithTopics.unit.id,
            subjectId = unitWithTopics.unit.subjectId,
            title = unitWithTopics.unit.title,
            position = unitWithTopics.unit.position,
            topics = unitWithTopics.topics.sortedBy { it.position }.map { it.toModel() },
        )
    },
)

internal fun StudyWindowEntity.toModel() =
    StudyWindow(id = id, weekday = DayOfWeek.of(weekday), startMinute = startMinute, endMinute = endMinute)

internal fun DayDetail.toModel() = DayBreakdown(
    day = record.epochDay,
    totalPoints = record.totalPoints,
    state = enumValueOrDefault(record.state, DayState.GREEN),
    decisiveStateRuleId = record.decisiveStateRuleId,
    contributions = contributions.sortedBy { it.position }.map {
        Contribution(
            ruleId = it.ruleId,
            label = it.label,
            category = enumValueOrDefault(it.category, Category.TASK_COMPLETION),
            metric = it.metric,
            value = it.value,
            rawPoints = it.rawPoints,
            points = it.points,
            limited = it.limited,
        )
    },
    metrics = metrics.associate { it.metric to it.value },
    findings = findings.map {
        StateFindingModel(it.ruleId, it.label, it.metric, it.value, enumValueOrDefault(it.state, DayState.GREEN))
    },
)

/** Stored enum names survive engine upgrades that rename or drop values. */
internal inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
