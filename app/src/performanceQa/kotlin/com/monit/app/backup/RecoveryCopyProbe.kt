package com.monit.app.backup

import java.io.IOException
import java.security.UnrecoverableKeyException

internal class RecoveryCopyProbe {
    var injected = false
        private set
    var failurePoint: RecoveryCopyCheckpoint? = null

    fun checkpoint(point: RecoveryCopyCheckpoint) {
        if (point != failurePoint) return
        injected = true
        if (point == RecoveryCopyCheckpoint.BEFORE_KEY_ACCESS) throw UnrecoverableKeyException("Synthetic key access failure")
        throw IOException("Synthetic recovery copy failure")
    }
}
