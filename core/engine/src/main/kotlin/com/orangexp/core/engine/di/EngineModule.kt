package com.orangexp.core.engine.di

import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.UniffiOrangeEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface EngineModule {
    @Binds
    fun engine(impl: UniffiOrangeEngine): OrangeEngine
}
