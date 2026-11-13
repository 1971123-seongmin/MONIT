package com.monit.app.backup

import com.monit.app.notifications.ReminderCoordinator
import com.monit.core.model.BackupRepository
import com.monit.core.model.RecoveryCopyStore
import com.monit.core.model.RestoreResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

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
) : BackupNotifications {
    override suspend fun reconcile(result: RestoreResult): RestoreResult {
        coordinator.reconcile()
        return repository.notificationsReady(result.id)
    }
}
