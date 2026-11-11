package com.monit.core.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.Navigator as AndroidNavigator
import androidx.navigation.createGraph
import kotlinx.serialization.Serializable
import org.robolectric.RuntimeEnvironment

@Serializable
internal data object Start

@Serializable
internal data class Form(val entryId: String)

@Serializable
internal data object Registration

/** 실제 NavController의 stack/수명을 사용하며 실패 주입은 테스트용 destination 실행기에만 둔다. */
internal class NavigationFixture(savedState: android.os.Bundle? = null) {
    val owner = FixtureLifecycleOwner()
    val store = ViewModelStore()
    val probe = ProbeNavigator()
    val controller = NavHostController(RuntimeEnvironment.getApplication())
    val navigator: NavControllerNavigator

    init {
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        controller.setLifecycleOwner(owner)
        controller.setViewModelStore(store)
        controller.navigatorProvider.addNavigator(probe)
        savedState?.let(controller::restoreState)
        controller.graph = controller.createGraph(startDestination = Start) {
            destination(NavDestinationBuilder(probe, Start::class, emptyMap()))
            destination(NavDestinationBuilder(probe, Form::class, emptyMap()))
            destination(NavDestinationBuilder(probe, Registration::class, emptyMap()))
        }
        navigator = NavControllerNavigator(controller)
        check(current.lifecycle.currentState == Lifecycle.State.RESUMED)
    }

    val current: NavBackStackEntry get() = requireNotNull(controller.currentBackStackEntry)

    fun close() {
        navigator.close()
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

internal class FixtureLifecycleOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry(this)
}

@AndroidNavigator.Name("probe")
internal class ProbeNavigator : AndroidNavigator<NavDestination>() {
    var refusePop = false
    var throwOnPop = false

    override fun createDestination() = NavDestination(this)

    override fun navigate(
        entries: List<NavBackStackEntry>,
        navOptions: NavOptions?,
        navigatorExtras: Extras?,
    ) {
        entries.forEach { state.push(it) }
    }

    override fun popBackStack(popUpTo: NavBackStackEntry, savedState: Boolean) {
        check(!throwOnPop) { "Synthetic pop failure" }
        if (!refusePop) state.pop(popUpTo, savedState)
    }
}
