package com.monit.core.common.io

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/** 화면/VM이 교체돼도 이전 실제 작업과 정리 child가 종료하기 전 새 파일 작업을 시작하지 않는다. */
@Singleton
class LocalDataWorkGate @Inject constructor() {
    private val mutex = Mutex()
    val held: Boolean get() = mutex.isLocked
    fun acquire(): Any? = Any().takeIf { mutex.tryLock(it) }
    fun release(owner: Any) = mutex.unlock(owner)
}
