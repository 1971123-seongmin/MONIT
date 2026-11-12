package com.monit.feature.accounts.importing

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface ImportDocumentModule {
    @Binds
    @Singleton
    fun documentReader(implementation: ContentImportDocumentReader): ImportDocumentReader
}
