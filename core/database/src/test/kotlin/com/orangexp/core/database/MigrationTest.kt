package com.orangexp.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Existing users' data must survive every schema upgrade. A database is built
 * from an older exported schema, filled, then opened by the current
 * [OrangeXpDatabase], which runs the migrations and validates the result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version1DataSurvivesMigrationToCurrent() = runTest {
        createFromSchema(version = 1) { db ->
            db.execSQL("INSERT INTO subjects (id, name, examEpochDay, priority) VALUES (1, 'Maths', NULL, 4)")
            db.execSQL(
                "INSERT INTO timetable_slots (id, title, weekday, startMinute, endMinute, location, teacher, notes, " +
                    "requiresTravel, travelMinutes, preparationMinutes) VALUES (1, 'Maths', 1, 495, 555, 'R1', 'T', '', 1, NULL, NULL)",
            )
        }

        val db = Room.databaseBuilder(context, OrangeXpDatabase::class.java, DB).allowMainThreadQueries().build()
        try {
            assertEquals(listOf("Maths"), db.syllabusDao().getSubjects().map { it.name })
            assertEquals(1, db.timetableDao().getAll().size)
            assertEquals(0, db.competitionDao().getAll().size)
        } finally {
            db.close()
        }
    }

    /** Creates [DB] exactly as Room would have at [version], using the committed schema JSON. */
    private fun createFromSchema(version: Int, fill: (SupportSQLiteDatabase) -> Unit) {
        context.deleteDatabase(DB)
        val schema = javaClass.classLoader!!
            .getResourceAsStream("${OrangeXpDatabase::class.java.name}/$version.json")
            ?.bufferedReader()?.use { it.readText() }
            ?.let { JSONObject(it).getJSONObject("database") }
            ?: error("Exported schema $version not found in test assets")

        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(DB).callback(callback).build(),
        )
        fill(helper.writableDatabase)
        helper.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
