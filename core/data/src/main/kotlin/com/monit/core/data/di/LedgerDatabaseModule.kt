package com.monit.core.data.di

import android.content.Context
import com.monit.core.data.LedgerDatabase
import com.monit.core.data.createLedgerDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object LedgerDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LedgerDatabase =
        createLedgerDatabase(context, "monit-ledger.db")
}
