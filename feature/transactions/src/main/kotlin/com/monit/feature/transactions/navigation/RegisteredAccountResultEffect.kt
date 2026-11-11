package com.monit.feature.transactions.navigation

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry

/**
 * entry의 UI 반환을 소유 ViewModel의 검증 메서드에 전달한다.
 * onResult가 현재 폼/슬롯/context 반영 또는 기존 소비를 확인한 경우만 해당 키를 제거한다.
 */
@Composable
fun RegisteredAccountResultEffect(
    entry: NavBackStackEntry,
    selectionRequestId: String,
    onResult: (RegisteredAccountResult) -> Boolean,
    onInvalidResult: () -> Unit,
) {
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnInvalidResult by rememberUpdatedState(onInvalidResult)
    val key = RegisteredAccountResult.key(selectionRequestId)
    LaunchedEffect(entry, key) {
        entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            entry.savedStateHandle.getStateFlow<Any?>(key, null).collect { raw ->
                if (raw != null) {
                    val result = (raw as? Bundle)?.let(RegisteredAccountResult::fromBundle)
                    if (result == null || result.selectionRequestId != selectionRequestId) {
                        currentOnInvalidResult()
                    } else if (currentOnResult(result)) {
                        entry.savedStateHandle.remove<Bundle>(key)
                    }
                }
            }
        }
    }
}
