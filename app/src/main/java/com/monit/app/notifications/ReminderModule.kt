package com.monit.app.notifications

import com.monit.core.model.ReminderAccessSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface ReminderModule {
    @Binds fun platform(value: AndroidReminderPlatform): ReminderPlatform

    @Binds fun access(value: AndroidReminderPlatform): ReminderAccessSource
}
