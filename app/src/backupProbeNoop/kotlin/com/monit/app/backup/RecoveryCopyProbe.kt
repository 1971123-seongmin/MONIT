package com.monit.app.backup

/** 일반 산출물에는 실패 설정 입구가 없다. */
internal class RecoveryCopyProbe {
    fun checkpoint(point: RecoveryCopyCheckpoint) = Unit
}
