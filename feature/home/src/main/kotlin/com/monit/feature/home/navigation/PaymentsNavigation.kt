package com.monit.feature.home.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.monit.core.model.paymentMonth
import com.monit.core.model.requireLedgerId
import com.monit.core.navigation.NavigationResult
import com.monit.core.navigation.Navigator
import kotlinx.serialization.Serializable

@Serializable
data class PaymentsDestination(val entryId: String, val month: String? = null, val itemId: String? = null) {
    init {
        require(entryId.isNotBlank())
        month?.let(::paymentMonth)
        itemId?.let(::requireLedgerId)
        require(itemId == null || month != null)
    }
}
fun Navigator.navigateToPayments(source: NavBackStackEntry, destination: PaymentsDestination): NavigationResult = navigate(source, destination)
fun NavGraphBuilder.paymentsScreen(content: @Composable (NavBackStackEntry, PaymentsDestination) -> Unit) {
    composable<PaymentsDestination> {
        content(
            it,
            it.toRoute(),
        )
    }
}
