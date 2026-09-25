package com.orangexp.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orangexp.core.database.dao.DaySnapshot
import com.orangexp.core.database.model.AssistantMessageEntity
import com.orangexp.core.database.model.DayCategoryPointsEntity
import com.orangexp.core.database.model.DayContributionEntity
import com.orangexp.core.database.model.DayRecordEntity
import com.orangexp.core.database.model.DeviceEventEntity
import com.orangexp.core.database.model.ReminderCompletionEntity
import com.orangexp.core.database.model.ReminderEntity
import com.orangexp.core.database.model.SleepSessionEntity
import com.orangexp.core.database.model.StudySessionEntity
import com.orangexp.core.database.model.SubjectEntity
import com.orangexp.core.database.model.TopicEntity
import com.orangexp.core.database.model.UnitEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseTest {

    private lateinit var db: OrangeXpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrangeXpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun snapshot(day: Int, points: Long, contributions: Int) = DaySnapshot(
        record = DayRecordEntity(day, points, "GREEN", null, 0, "test"),
        contributions = (0 until contributions).map {
            DayContributionEntity(day, "rule$it", it, "Rule $it", "SLEEP", "m", 1.0, 1.0, points / contributions, false)
        },
        metrics = emptyList(),
        categories = listOf(DayCategoryPointsEntity(day, "SLEEP", points)),
        findings = emptyList(),
    )

    @Test
    fun replacingADayRemovesStaleBreakdownRows() = runTest {
        val dao = db.dayRecordDao()
        dao.replaceDay(snapshot(day = 100, points = 300, contributions = 3))
        dao.replaceDay(snapshot(day = 100, points = 200, contributions = 2))

        val detail = dao.observeDetail(100).first()!!
        assertEquals(200, detail.record.totalPoints)
        assertEquals(2, detail.contributions.size)
        assertEquals(listOf(200L), dao.observeAllWithCategories().first().single().categories.map { it.points })
    }

    @Test
    fun duplicateDeviceEventsAreIgnored() = runTest {
        val dao = db.deviceEventDao()
        val events = listOf(DeviceEventEntity(1, "UNLOCK"), DeviceEventEntity(2, "SCREEN_OFF"))
        dao.insertAll(events)
        dao.insertAll(events + DeviceEventEntity(3, "UNLOCK"))
        assertEquals(3, dao.between(0, 10).size)
        dao.deleteBefore(3)
        assertEquals(1, dao.between(0, 10).size)
    }

    @Test
    fun replacingDetectedSleepKeepsManualSessions() = runTest {
        val dao = db.sleepDao()
        fun session(end: Long, manual: Boolean) =
            SleepSessionEntity(0, end - 10, end - 5, end, 5, 0, 0, 50, "MEDIUM", "INACTIVITY_GAP", manual)
        dao.insertAll(listOf(session(100, false), session(200, false), session(210, true)))
        dao.replaceDetected(150, listOf(session(250, false)))
        assertEquals(listOf(100L, 210L, 250L), dao.endingBetween(0, 1_000).map { it.endMs })
    }

    @Test
    fun deletingASubjectCascadesToItsSyllabus() = runTest {
        val dao = db.syllabusDao()
        val subject = dao.upsertSubject(SubjectEntity(name = "DSA"))
        val unit = dao.upsertUnit(UnitEntity(subjectId = subject, title = "Lists", position = 0))
        dao.upsertTopic(TopicEntity(unitId = unit, subjectId = subject, title = "Linked lists", position = 0, estimatedMinutes = 60))
        assertEquals(1, dao.observeTree().first().single().units.single().topics.size)
        dao.deleteSubject(subject)
        assertEquals(emptyList(), dao.getTopics())
    }

    @Test
    fun runningStudySessionIsTracked() = runTest {
        val dao = db.studyDao()
        val id = dao.insertSession(StudySessionEntity(topicId = 1, subjectId = 1, startMs = 1_000, endMs = null))
        assertEquals(id, dao.getRunning()!!.id)
        assertEquals(1, dao.overlapping(0, 5_000).size)
        dao.updateSession(dao.getRunning()!!.copy(endMs = 61_000))
        assertNull(dao.getRunning())
        assertEquals(60_000, dao.studiedPerTopic().single().totalMs)
    }

    @Test
    fun activeRemindersAreOrderedByNextFireAndFinishedOnesMoveAway() = runTest {
        val dao = db.reminderDao()
        val later = dao.upsert(ReminderEntity(title = "Later", sourceText = "", kind = "REMINDER", atMs = 5_000, nextFireMs = 5_000, createdMs = 0))
        val sooner = dao.upsert(ReminderEntity(title = "Sooner", sourceText = "", kind = "DEADLINE", atMs = 9_000, nextFireMs = 2_000, createdMs = 0))
        assertEquals(listOf(sooner, later), dao.observeActive().first().map { it.id })

        dao.finish(sooner, "DONE", completedMs = 3_000)
        assertEquals(listOf(later), dao.getActive().map { it.id })
        assertEquals(listOf(sooner), dao.observeFinished(10).first().map { it.id })
        assertNull(dao.get(sooner)!!.nextFireMs)

        dao.insertCompletion(ReminderCompletionEntity(reminderId = sooner, completedMs = 3_000))
        dao.delete(sooner)
        assertEquals(1, dao.completionsBetween(0, 10_000), "completions outlive their reminder")
    }

    @Test
    fun conversationKeepsTheNewestMessagesInOrder() = runTest {
        val dao = db.assistantMessageDao()
        (1..5).forEach { dao.insert(AssistantMessageEntity(role = "USER", text = "m$it", timestampMs = it.toLong())) }
        assertEquals(listOf("m4", "m5"), dao.recent(2).map { it.text })
    }
}
