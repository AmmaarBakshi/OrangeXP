package com.orangexp.core.sensing.di

import com.orangexp.core.sensing.BatteryPowerStateSource
import com.orangexp.core.sensing.DeviceUsageSource
import com.orangexp.core.sensing.HardwareStepCounterSource
import com.orangexp.core.sensing.PowerStateSource
import com.orangexp.core.sensing.StepCounterSource
import com.orangexp.core.sensing.UsageStatsDeviceUsageSource
import com.orangexp.core.sensing.movement.MovementTracker
import com.orangexp.core.sensing.movement.PlayServicesMovementTracker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface SensingModule {
    @Binds fun deviceUsage(impl: UsageStatsDeviceUsageSource): DeviceUsageSource
    @Binds fun steps(impl: HardwareStepCounterSource): StepCounterSource
    @Binds fun power(impl: BatteryPowerStateSource): PowerStateSource
    @Binds fun movement(impl: PlayServicesMovementTracker): MovementTracker
}
