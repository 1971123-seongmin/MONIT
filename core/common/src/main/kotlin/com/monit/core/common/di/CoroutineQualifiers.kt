package com.monit.core.common.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** ViewModel 생성자에 전달해 ViewModel이 취소 책임을 갖는 Scope. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ViewModelCoroutineScope
