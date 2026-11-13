package com.monit.core.data.di

import com.monit.core.data.RoomDraftRepository
import com.monit.core.data.RoomLedgerRepository
import com.monit.core.data.RoomPaymentRepository
import com.monit.core.data.RoomReminderRepository
import com.monit.core.model.DraftRepository
import com.monit.core.model.LedgerRepository
import com.monit.core.model.PaymentRepository
import com.monit.core.model.ReminderRepository
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
    fun reminders(implementation: RoomReminderRepository): ReminderRepository

    @Binds
    @Singleton
    fun payments(implementation: RoomPaymentRepository): PaymentRepository

    @Binds
    @Singleton
    fun repository(implementation: RoomLedgerRepository): LedgerRepository

    @Binds
    @Singleton
    fun drafts(implementation: RoomDraftRepository): DraftRepository
}
