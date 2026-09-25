package com.orangexp.core.database.di

import android.content.Context
import androidx.room.Room
import com.orangexp.core.database.OrangeXpDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): OrangeXpDatabase =
        Room.databaseBuilder(context, OrangeXpDatabase::class.java, "orangexp.db").build()

    @Provides fun deviceEventDao(db: OrangeXpDatabase) = db.deviceEventDao()
    @Provides fun keyValueDao(db: OrangeXpDatabase) = db.keyValueDao()
    @Provides fun stepsDao(db: OrangeXpDatabase) = db.stepsDao()
    @Provides fun sleepDao(db: OrangeXpDatabase) = db.sleepDao()
    @Provides fun dayRecordDao(db: OrangeXpDatabase) = db.dayRecordDao()
    @Provides fun timetableDao(db: OrangeXpDatabase) = db.timetableDao()
    @Provides fun syllabusDao(db: OrangeXpDatabase) = db.syllabusDao()
    @Provides fun studyDao(db: OrangeXpDatabase) = db.studyDao()
    @Provides fun attendanceDao(db: OrangeXpDatabase) = db.attendanceDao()
    @Provides fun travelDao(db: OrangeXpDatabase) = db.travelDao()
    @Provides fun movementDao(db: OrangeXpDatabase) = db.movementDao()
    @Provides fun competitionDao(db: OrangeXpDatabase) = db.competitionDao()
    @Provides fun reminderDao(db: OrangeXpDatabase) = db.reminderDao()
    @Provides fun assistantMessageDao(db: OrangeXpDatabase) = db.assistantMessageDao()
}
