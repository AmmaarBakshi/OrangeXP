package com.orangexp.core.data.di

import com.orangexp.core.data.repository.AcademicRepository
import com.orangexp.core.data.repository.BriefingRepository
import com.orangexp.core.data.repository.ConversationRepository
import com.orangexp.core.data.repository.HolstromSettingsRepository
import com.orangexp.core.data.repository.OfflineBriefingRepository
import com.orangexp.core.data.repository.OfflineConversationRepository
import com.orangexp.core.data.repository.OfflineHolstromSettingsRepository
import com.orangexp.core.data.repository.OfflineReminderRepository
import com.orangexp.core.data.repository.ReminderRepository
import com.orangexp.core.data.repository.CompetitionRepository
import com.orangexp.core.data.repository.OfflineCompetitionRepository
import com.orangexp.core.data.repository.ConfigRepository
import com.orangexp.core.data.repository.DataChangeListener
import com.orangexp.core.data.repository.DayRepository
import com.orangexp.core.data.repository.MovementRepository
import com.orangexp.core.data.repository.OfflineMovementRepository
import com.orangexp.core.data.repository.OfflineAcademicRepository
import com.orangexp.core.data.repository.OfflineConfigRepository
import com.orangexp.core.data.repository.OfflineDayRepository
import com.orangexp.core.data.repository.OfflineTravelRepository
import com.orangexp.core.data.repository.TravelRepository
import com.orangexp.core.data.tracking.DefaultTrackingCoordinator
import com.orangexp.core.data.tracking.TrackingCoordinator
import com.orangexp.core.sensing.movement.MovementSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {
    @Binds fun config(impl: OfflineConfigRepository): ConfigRepository
    @Binds fun day(impl: OfflineDayRepository): DayRepository
    @Binds fun academic(impl: OfflineAcademicRepository): AcademicRepository
    @Binds fun travel(impl: OfflineTravelRepository): TravelRepository
    @Binds fun tracking(impl: DefaultTrackingCoordinator): TrackingCoordinator
    @Binds fun movement(impl: OfflineMovementRepository): MovementRepository
    @Binds fun movementSink(impl: OfflineMovementRepository): MovementSink
    @Binds fun competitions(impl: OfflineCompetitionRepository): CompetitionRepository
    @Binds fun holstromSettings(impl: OfflineHolstromSettingsRepository): HolstromSettingsRepository
    @Binds fun reminders(impl: OfflineReminderRepository): ReminderRepository
    @Binds fun conversation(impl: OfflineConversationRepository): ConversationRepository
    @Binds fun briefing(impl: OfflineBriefingRepository): BriefingRepository

    /** Empty unless a module (e.g. widgets) contributes listeners. */
    @Multibinds fun changeListeners(): Set<DataChangeListener>
}
