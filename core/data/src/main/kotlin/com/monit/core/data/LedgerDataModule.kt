package com.monit.core.data

import android.content.Context
import com.monit.core.model.LedgerRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object LedgerDataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LedgerDatabase =
        createLedgerDatabase(context, "monit-ledger.db")

    @Provides
    @Singleton
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))

    @Provides
    @Singleton
    fun repository(database: LedgerDatabase, clock: Clock): LedgerRepository =
        RoomLedgerRepository(database, clock)
}
