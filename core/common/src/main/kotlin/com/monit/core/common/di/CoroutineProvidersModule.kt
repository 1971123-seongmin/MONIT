package com.monit.core.common.di

import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.coroutines.CoroutineScopeProvider
import com.monit.core.common.coroutines.DefaultCoroutineContextProvider
import com.monit.core.common.coroutines.DefaultCoroutineScopeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface CoroutineProvidersModule {
    @Binds
    @Singleton
    fun contexts(implementation: DefaultCoroutineContextProvider): CoroutineContextProvider

    @Binds
    @Singleton
    fun scopes(implementation: DefaultCoroutineScopeProvider): CoroutineScopeProvider
}
