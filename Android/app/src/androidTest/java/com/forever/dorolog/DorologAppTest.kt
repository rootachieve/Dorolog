package com.forever.dorolog

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DorologAppTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher

    @Test
    fun touchBack_fromBothDestinations_restoresReadyMainScreen() {
        setAppContent()

        for ((button, screen) in destinations) {
            openScreen(button, screen)
            compose.onNodeWithText("뒤로가기").assertIsEnabled().performClick()
            compose.mainClock.advanceTimeByFrame()
            assertMainReady()
        }
    }

    @Test
    fun physicalBack_fromBothDestinations_returnsMainWithoutBackgrounding() {
        val backgrounds = AtomicInteger()
        setAppContent { backgrounds.incrementAndGet() }

        for ((button, screen) in destinations) {
            openScreen(button, screen)
            compose.runOnIdle { backDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            assertMainReady()
        }
        compose.runOnIdle { assertEquals(0, backgrounds.get()) }
    }

    @Test
    fun destination_survivesSavedStateRestoration() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DorologApp(onBackground = {}, animationsEnabled = false)
        }

        for ((button, screen) in destinations) {
            openScreen(button, screen)
            restoration.emulateSavedInstanceStateRestore()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag(screen).assertIsDisplayed()
            compose.onNodeWithText("시작하기").assertDoesNotExist()
            compose.onNodeWithText("뒤로가기").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertMainReady()
        }
    }

    @Test
    fun physicalBack_onMain_callsBackgroundAndKeepsMainState() {
        val backgrounds = AtomicInteger()
        setAppContent { backgrounds.incrementAndGet() }

        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()

        compose.runOnIdle { assertEquals(1, backgrounds.get()) }
        assertMainReady()
    }

    @Test
    fun physicalBack_duringWhiteFade_doesNotBackgroundOrCancelNavigation() {
        val backgrounds = AtomicInteger()
        setAppContent { backgrounds.incrementAndGet() }

        compose.onNodeWithText("시작하기").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(500)

        compose.runOnIdle { assertEquals(0, backgrounds.get()) }
        compose.onNodeWithTag("departure-screen").assertIsDisplayed()
    }

    private fun setAppContent(onBackground: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DorologApp(onBackground = onBackground, animationsEnabled = false)
        }
    }

    private fun openScreen(button: String, screen: String) {
        compose.onNodeWithText(button).assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag(screen).assertIsDisplayed()
        compose.onNodeWithText("시작하기").assertDoesNotExist()
        compose.onNodeWithText("뒤로가기").assertIsDisplayed()
    }

    private fun assertMainReady() {
        compose.onNodeWithText("시작하기").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("이야기 보관함").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
        compose.onNodeWithTag("archive-screen").assertDoesNotExist()
    }

    private val destinations = listOf("시작하기" to "departure-screen", "이야기 보관함" to "archive-screen")
}
