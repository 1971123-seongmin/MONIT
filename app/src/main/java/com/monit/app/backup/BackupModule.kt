package com.monit.app.backup

import com.monit.app.notifications.ReminderCoordinator
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.BackupRepository
import com.monit.core.model.RecoveryCopyStore
import com.monit.core.model.RestoreResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.withContext

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BackupModule {
    @Binds abstract fun documents(value: BackupDocuments): BackupDocumentAccess

    @Binds abstract fun recovery(value: KeystoreRecoveryCopyStore): RecoveryCopyStore

    @Binds abstract fun notifications(value: BackupNotificationsImpl): BackupNotifications
}

internal interface BackupNotifications {
    suspend fun reconcile(result: RestoreResult): RestoreResult
}

internal class BackupNotificationsImpl @Inject constructor(
    private val coordinator: ReminderCoordinator,
    private val repository: BackupRepository,
    private val contexts: CoroutineContextProvider,
) : BackupNotifications {
    override suspend fun reconcile(result: RestoreResult): RestoreResult = withContext(contexts.io) {
        coordinator.reconcile()
        repository.notificationsReady(result.id)
    }
}
