package com.monit.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object LedgerClockModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}
