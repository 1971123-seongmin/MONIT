package com.monit.core.common.coroutines

import com.monit.core.common.di.DefaultDispatcher
import com.monit.core.common.di.IoDispatcher
import com.monit.core.common.di.MainDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

interface CoroutineContextProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

internal class DefaultCoroutineContextProvider @Inject constructor(
    @MainDispatcher override val main: CoroutineDispatcher,
    @IoDispatcher override val io: CoroutineDispatcher,
    @DefaultDispatcher override val default: CoroutineDispatcher,
) : CoroutineContextProvider
