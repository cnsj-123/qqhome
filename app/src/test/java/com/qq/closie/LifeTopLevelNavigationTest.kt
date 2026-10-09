package com.qq.closie

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.navigation.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LifeTopLevelNavigationTest {
    private lateinit var nav: TestNavHostController

    @Before fun setup() {
        nav = TestNavHostController(ApplicationProvider.getApplicationContext())
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        nav.setLifecycleOwner(owner)
        nav.setViewModelStore(ViewModelStore())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.graph = nav.createGraph(startDestination = LifeOsRoute.HOME, route = LifeOsRoute.GRAPH) {
            listOf(LifeOsRoute.HOME, LifeOsRoute.MAP, LifeOsRoute.CALENDAR, LifeOsRoute.SETTINGS,
                LifeOsRoute.APPEARANCE, LifeOsRoute.CLOSET, LifeOsRoute.MODULE, "life/finance")
                .forEach { composable(it) {} }
        }
    }

    @Test fun repeatedModuleVisitsAlwaysReturnToHome() {
        repeat(20) {
            listOf(LifeOsRoute.MAP, LifeOsRoute.CALENDAR, LifeOsRoute.module("food"), "life/finance").forEach { route ->
                nav.navigateLifeTopLevel(route)
                assertNotNull(nav.currentDestination)
                assertNotNull(nav.getBackStackEntry(LifeOsRoute.HOME))
                nav.returnToLifeHome()
                assertEquals(LifeOsRoute.HOME, nav.currentDestination?.route)
            }
        }
    }

    @Test fun replacingOrRepeatingTopLevelDoesNotAccumulateModules() {
        nav.navigateLifeTopLevel(LifeOsRoute.MAP)
        nav.navigateLifeTopLevel(LifeOsRoute.CALENDAR)
        repeat(5) { nav.navigateLifeTopLevel(LifeOsRoute.CALENDAR) }
        assertTrue(runCatching { nav.getBackStackEntry(LifeOsRoute.MAP) }.isFailure)
        assertTrue(nav.popBackStack()) // System Back goes straight to Home.
        assertEquals(LifeOsRoute.HOME, nav.currentDestination?.route)
    }

    @Test fun appearanceKeepsItsSettingsParent() {
        nav.navigateLifeTopLevel(LifeOsRoute.SETTINGS)
        nav.navigate(LifeOsRoute.APPEARANCE)
        nav.returnWithinLifeModule()
        assertEquals(LifeOsRoute.SETTINGS, nav.currentDestination?.route)
        nav.returnToLifeHome()
        assertEquals(LifeOsRoute.HOME, nav.currentDestination?.route)
    }

    @Test fun repeatedCloseCallbacksCannotPopTheHomeBase() {
        nav.navigateLifeTopLevel(LifeOsRoute.MAP)
        repeat(10) { nav.returnToLifeHome() }
        repeat(10) { nav.returnWithinLifeModule() }
        assertEquals(LifeOsRoute.HOME, nav.currentDestination?.route)
        assertNotNull(nav.getBackStackEntry(LifeOsRoute.HOME))
        nav.navigateLifeTopLevel(LifeOsRoute.SETTINGS)
        nav.navigate(LifeOsRoute.APPEARANCE)
        nav.navigateLifeTopLevel(LifeOsRoute.CALENDAR)
        assertTrue(runCatching { nav.getBackStackEntry(LifeOsRoute.APPEARANCE) }.isFailure)
        nav.returnToLifeHome()
        assertEquals(LifeOsRoute.HOME, nav.currentDestination?.route)
    }

    @Test fun missingHomeIsRepairedBeforeOpeningOrReturning() {
        nav.popBackStack(LifeOsRoute.HOME, inclusive = true)
        assertNull(nav.currentDestination)
        nav.returnToLifeHome()
        assertEquals(LifeOsRoute.HOME, nav.currentDestination?.route)
        nav.popBackStack(LifeOsRoute.HOME, inclusive = true)
        nav.navigateLifeTopLevel(LifeOsRoute.CLOSET)
        nav.returnToLifeHome()
        assertEquals(LifeOsRoute.HOME, nav.currentDestination?.route)
    }
}
