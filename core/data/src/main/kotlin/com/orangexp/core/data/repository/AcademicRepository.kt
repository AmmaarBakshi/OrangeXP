package com.orangexp.core.data.repository

import com.orangexp.core.common.coroutines.Dispatcher
import com.orangexp.core.common.coroutines.OxDispatchers
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.common.time.today
import com.orangexp.core.data.model.AttendanceStatus
import com.orangexp.core.data.model.RunningStudy
import com.orangexp.core.data.model.StudyPlanItem
import com.orangexp.core.data.model.StudyWindow
import com.orangexp.core.data.model.Subject
import com.orangexp.core.data.model.SubjectForecast
import com.orangexp.core.data.model.TimetableEntry
import com.orangexp.core.data.model.toEngine
import com.orangexp.core.data.model.toEntity
import com.orangexp.core.data.model.toModel
import com.orangexp.core.database.dao.AttendanceDao
import com.orangexp.core.database.dao.KeyValueDao
import com.orangexp.core.database.dao.StudyDao
import com.orangexp.core.database.dao.SyllabusDao
import com.orangexp.core.database.dao.TimetableDao
import com.orangexp.core.database.model.AttendanceEntity
import com.orangexp.core.database.model.KeyValueEntity
import com.orangexp.core.database.model.StudyPlanEntity
import com.orangexp.core.database.model.StudySessionEntity
import com.orangexp.core.database.model.StudyWindowEntity
import com.orangexp.core.database.model.SubjectEntity
import com.orangexp.core.database.model.SubjectForecastEntity
import com.orangexp.core.database.model.TopicEntity
import com.orangexp.core.database.model.UnitEntity
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.CapacityInput
import com.orangexp.core.engine.ffi.ScheduleInput
import com.orangexp.core.engine.ffi.SyllabusSubject
import com.orangexp.core.engine.ffi.SyllabusTopic
import com.orangexp.core.engine.ffi.TimetableConflict
import com.orangexp.core.engine.ffi.TimetableSlot
import com.orangexp.core.engine.ffi.StudyWindow as EngineStudyWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/** Timetable, syllabus, the generated study plan and the record of real study. */
interface AcademicRepository {
    val timetable: Flow<List<TimetableEntry>>
    val timetableConflicts: Flow<List<TimetableConflict>>
    val subjects: Flow<List<Subject>>
    val studyWindows: Flow<List<StudyWindow>>
    val forecasts: Flow<List<SubjectForecast>>
    val runningStudy: Flow<RunningStudy?>

    fun plan(from: EpochDay, to: EpochDay): Flow<List<StudyPlanItem>>
    fun attendance(day: EpochDay): Flow<Map<Long, AttendanceStatus>>

    suspend fun saveTimetableEntry(entry: TimetableEntry)
    suspend fun deleteTimetableEntry(id: Long)

    suspend fun saveSubject(id: Long, name: String, examDay: EpochDay?, priority: Int): Long
    suspend fun deleteSubject(id: Long)
    suspend fun addUnit(subjectId: Long, title: String): Long
    suspend fun deleteUnit(id: Long)
    suspend fun addTopic(unitId: Long, subjectId: Long, title: String, estimatedMinutes: Int, difficulty: Int): Long
    suspend fun deleteTopic(id: Long)
    suspend fun setTopicCompleted(topicId: Long, completed: Boolean)

    suspend fun addStudyWindow(weekday: DayOfWeek, startMinute: Int, endMinute: Int)
    suspend fun deleteStudyWindow(window: StudyWindow)

    suspend fun startStudy(topicId: Long?)
    suspend fun stopStudy()

    suspend fun setAttendance(day: EpochDay, slotId: Long, status: AttendanceStatus?)

    /** Re-plans all remaining syllabus work from today. Deterministic; safe to call often. */
    suspend fun regeneratePlan()
}

@Singleton
internal class OfflineAcademicRepository @Inject constructor(
    private val timetableDao: TimetableDao,
    private val syllabusDao: SyllabusDao,
    private val studyDao: StudyDao,
    private val attendanceDao: AttendanceDao,
    private val keyValues: KeyValueDao,
    private val configRepository: ConfigRepository,
    private val engine: OrangeEngine,
    private val time: TimeSource,
    @Dispatcher(OxDispatchers.Default) private val dispatcher: CoroutineDispatcher,
) : AcademicRepository {

    override val timetable: Flow<List<TimetableEntry>> =
        timetableDao.observeAll().map { slots -> slots.map { it.toModel() } }

    override val timetableConflicts: Flow<List<TimetableConflict>> =
        timetable.map { entries -> engine.findTimetableConflicts(entries.map { it.toSlot() }) }.flowOn(dispatcher)

    override val subjects: Flow<List<Subject>> =
        syllabusDao.observeTree().map { tree -> tree.map { it.toModel() } }

    override val studyWindows: Flow<List<StudyWindow>> =
        timetableDao.observeStudyWindows().map { windows -> windows.map { it.toModel() } }

    override val forecasts: Flow<List<SubjectForecast>> =
        combine(studyDao.observeForecasts(), syllabusDao.observeTree()) { forecasts, tree ->
            val names = tree.associate { it.subject.id to it.subject.name }
            forecasts.mapNotNull { f ->
                SubjectForecast(
                    subjectId = f.subjectId,
                    subjectName = names[f.subjectId] ?: return@mapNotNull null,
                    status = enumValueOf(f.status),
                    remainingMinutes = f.remainingMinutes,
                    scheduledMinutes = f.scheduledMinutes,
                    shortfallMinutes = f.shortfallMinutes,
                    targetDay = f.targetDay,
                    projectedFinishDay = f.projectedFinishDay,
                )
            }.sortedBy { it.subjectName }
        }

    override val runningStudy: Flow<RunningStudy?> =
        studyDao.observeRunning().map { s -> s?.let { RunningStudy(it.id, it.topicId, it.startMs) } }

    override fun plan(from: EpochDay, to: EpochDay): Flow<List<StudyPlanItem>> =
        combine(studyDao.observePlan(from, to), syllabusDao.observeTree()) { plan, tree ->
            val subjects = tree.associate { it.subject.id to it.subject.name }
            val topics = tree.flatMap { s -> s.units.flatMap { it.topics } }.associateBy { it.id }
            plan.mapNotNull { item ->
                val topic = topics[item.topicId] ?: return@mapNotNull null
                StudyPlanItem(
                    day = item.epochDay,
                    topicId = item.topicId,
                    subjectId = item.subjectId,
                    topicTitle = topic.title,
                    subjectName = subjects[item.subjectId].orEmpty(),
                    minutes = item.minutes,
                    partIndex = item.partIndex,
                    partCount = item.partCount,
                    topicCompleted = topic.completedAtMs != null,
                )
            }
        }

    override fun attendance(day: EpochDay): Flow<Map<Long, AttendanceStatus>> =
        attendanceDao.observeDay(day).map { rows -> rows.associate { it.slotId to enumValueOf<AttendanceStatus>(it.status) } }

    override suspend fun saveTimetableEntry(entry: TimetableEntry) {
        require(entry.endMinute > entry.startMinute) { "A class must end after it starts" }
        timetableDao.upsert(entry.toEntity())
        regeneratePlan()
    }

    override suspend fun deleteTimetableEntry(id: Long) {
        timetableDao.delete(id)
        regeneratePlan()
    }

    override suspend fun saveSubject(id: Long, name: String, examDay: EpochDay?, priority: Int): Long {
        val saved = syllabusDao.upsertSubject(
            SubjectEntity(id = id, name = name.trim(), examEpochDay = examDay, priority = priority.coerceIn(1, 5)),
        )
        regeneratePlan()
        return if (id == 0L) saved else id
    }

    override suspend fun deleteSubject(id: Long) {
        syllabusDao.deleteSubject(id)
        regeneratePlan()
    }

    override suspend fun addUnit(subjectId: Long, title: String): Long =
        syllabusDao.upsertUnit(
            UnitEntity(subjectId = subjectId, title = title.trim(), position = syllabusDao.nextUnitPosition(subjectId)),
        )

    override suspend fun deleteUnit(id: Long) {
        syllabusDao.deleteUnit(id)
        regeneratePlan()
    }

    override suspend fun addTopic(
        unitId: Long,
        subjectId: Long,
        title: String,
        estimatedMinutes: Int,
        difficulty: Int,
    ): Long {
        val id = syllabusDao.upsertTopic(
            TopicEntity(
                unitId = unitId,
                subjectId = subjectId,
                title = title.trim(),
                position = syllabusDao.nextTopicPosition(unitId),
                estimatedMinutes = estimatedMinutes.coerceAtLeast(1),
                difficulty = difficulty.coerceIn(1, 5),
            ),
        )
        regeneratePlan()
        return id
    }

    override suspend fun deleteTopic(id: Long) {
        syllabusDao.deleteTopic(id)
        regeneratePlan()
    }

    override suspend fun setTopicCompleted(topicId: Long, completed: Boolean) {
        val topic = syllabusDao.getTopic(topicId) ?: return
        syllabusDao.updateTopic(topic.copy(completedAtMs = if (completed) time.nowMs() else null))
        regeneratePlan()
    }

    override suspend fun addStudyWindow(weekday: DayOfWeek, startMinute: Int, endMinute: Int) {
        require(endMinute > startMinute) { "A study window must end after it starts" }
        timetableDao.insertStudyWindows(listOf(StudyWindowEntity(weekday = weekday.value, startMinute = startMinute, endMinute = endMinute)))
        regeneratePlan()
    }

    override suspend fun deleteStudyWindow(window: StudyWindow) {
        timetableDao.deleteStudyWindow(
            StudyWindowEntity(window.id, window.weekday.value, window.startMinute, window.endMinute),
        )
        regeneratePlan()
    }

    override suspend fun startStudy(topicId: Long?) {
        stopStudy()
        val subjectId = topicId?.let { syllabusDao.getTopic(it)?.subjectId }
        studyDao.insertSession(StudySessionEntity(topicId = topicId, subjectId = subjectId, startMs = time.nowMs(), endMs = null))
    }

    override suspend fun stopStudy() {
        val running = studyDao.getRunning() ?: return
        studyDao.updateSession(running.copy(endMs = time.nowMs()))
    }

    override suspend fun setAttendance(day: EpochDay, slotId: Long, status: AttendanceStatus?) {
        if (status == null) {
            attendanceDao.clear(day, slotId)
        } else {
            attendanceDao.upsert(AttendanceEntity(day, slotId, status.name, time.nowMs()))
        }
    }

    override suspend fun regeneratePlan() = withContext(dispatcher) {
        seedDefaultStudyWindowsIfEmpty()
        val today = time.today()
        val config = configRepository.current()
        val subjects = syllabusDao.getSubjects()
        val topics = syllabusDao.getTopics()
        val studied = studyDao.studiedPerTopic().associate { it.topicId to (it.totalMs / 60_000).toInt() }
        // Topics are studied unit by unit, then in their order within the unit.
        val unitPositions = syllabusDao.getUnits().associate { it.id to it.position }

        val horizonEnd = maxOf(
            subjects.mapNotNull { it.examEpochDay }.maxOrNull() ?: today,
            today + config.study.defaultHorizonDays.toInt(),
        )
        val capacity = engine.studyCapacity(
            CapacityInput(
                slots = timetableDao.getAll().map { it.toModel().toSlot() },
                windows = timetableDao.getStudyWindows().map {
                    EngineStudyWindow(
                        weekday = DayOfWeek.of(it.weekday).toEngine(),
                        startMinute = it.startMinute.toUInt(),
                        endMinute = it.endMinute.toUInt(),
                    )
                },
                overrides = emptyList(),
                fromDay = today,
                toDay = horizonEnd,
                defaultTravelMinutes = config.travel.defaultTravelMinutes,
            ),
        )
        val schedule = engine.generateStudySchedule(
            ScheduleInput(
                today = today,
                subjects = subjects.map {
                    SyllabusSubject(
                        id = it.id.toString(),
                        name = it.name,
                        deadlineDay = it.examEpochDay,
                        priority = it.priority.toUByte(),
                    )
                },
                topics = topics.map {
                    SyllabusTopic(
                        id = it.id.toString(),
                        subjectId = it.subjectId.toString(),
                        sequence = (unitPositions[it.unitId] ?: 0).toUInt() * 10_000u + it.position.toUInt(),
                        estimatedMinutes = it.estimatedMinutes.toUInt(),
                        difficulty = it.difficulty.toUByte(),
                        completed = it.completedAtMs != null,
                        progressMinutes = (studied[it.id] ?: 0).toUInt(),
                    )
                },
                capacity = capacity,
                config = config.study,
            ),
        )

        val positions = HashMap<Int, Int>()
        val plan = schedule.assignments.map { a ->
            val position = positions.merge(a.epochDay, 1, Int::plus)!! - 1
            StudyPlanEntity(
                epochDay = a.epochDay,
                topicId = a.topicId.toLong(),
                subjectId = a.subjectId.toLong(),
                minutes = a.minutes.toInt(),
                partIndex = a.partIndex.toInt(),
                partCount = a.partCount.toInt(),
                position = position,
            )
        }
        val forecasts = schedule.forecasts.map { f ->
            SubjectForecastEntity(
                subjectId = f.subjectId.toLong(),
                status = f.status.name,
                remainingMinutes = f.remainingMinutes.toInt(),
                scheduledMinutes = f.scheduledMinutes.toInt(),
                shortfallMinutes = f.shortfallMinutes.toInt(),
                targetDay = f.targetDay,
                projectedFinishDay = f.projectedFinishDay,
                generatedForDay = today,
            )
        }
        studyDao.replacePlan(today, plan, forecasts)
        keyValues.put(KeyValueEntity(StorageKeys.PLAN_GENERATED_FOR_DAY, today.toString()))
    }

    private suspend fun seedDefaultStudyWindowsIfEmpty() {
        if (timetableDao.studyWindowCount() > 0) return
        val weekdays = (1..5).map { StudyWindowEntity(weekday = it, startMinute = 18 * 60, endMinute = 22 * 60) }
        val weekend = (6..7).flatMap {
            listOf(
                StudyWindowEntity(weekday = it, startMinute = 10 * 60, endMinute = 13 * 60),
                StudyWindowEntity(weekday = it, startMinute = 16 * 60, endMinute = 20 * 60),
            )
        }
        timetableDao.insertStudyWindows(weekdays + weekend)
    }
}

internal fun TimetableEntry.toSlot() = TimetableSlot(
    id = id.toString(),
    title = title,
    weekday = weekday.toEngine(),
    startMinute = startMinute.toUInt(),
    endMinute = endMinute.toUInt(),
    requiresTravel = requiresTravel,
    travelMinutes = travelMinutes?.toUInt(),
    preparationMinutes = preparationMinutes?.toUInt(),
)
