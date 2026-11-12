package com.monit.core.data.di

import com.monit.core.data.RoomDraftRepository
import com.monit.core.data.RoomLedgerRepository
import com.monit.core.model.DraftRepository
import com.monit.core.model.LedgerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface LedgerRepositoryModule {
    @Binds
    @Singleton
    fun repository(implementation: RoomLedgerRepository): LedgerRepository

    @Binds
    @Singleton
    fun drafts(implementation: RoomDraftRepository): DraftRepository
}
