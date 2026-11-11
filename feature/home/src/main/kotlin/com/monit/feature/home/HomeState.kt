package com.monit.feature.home

sealed interface HomeState {
    data object Loading : HomeState
    data class Ready(val ui: HomeUiState) : HomeState
    data object Failed : HomeState
}
