package com.forever.dorolog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun start_waitsForWhiteFade_thenNavigatesOnce() {
        val starts = AtomicInteger()
        val archives = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MainScreen(
                onStart = { starts.incrementAndGet() },
                onArchive = { archives.incrementAndGet() },
                animationsEnabled = false,
            )
        }

        compose.onNodeWithText("시작하기").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("시작하기").assertIsNotEnabled()
        compose.onNodeWithText("이야기 보관함").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle { assertEquals(0, starts.get()) }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle {
            assertEquals(1, starts.get())
            assertEquals(0, archives.get())
        }
    }

    @Test
    fun archive_waitsForWhiteFade_thenNavigatesToArchiveOnly() {
        val starts = AtomicInteger()
        val archives = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MainScreen(
                onStart = { starts.incrementAndGet() },
                onArchive = { archives.incrementAndGet() },
                animationsEnabled = false,
            )
        }

        compose.onNodeWithText("이야기 보관함").performClick()
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle { assertEquals(0, archives.get()) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {
            assertEquals(0, starts.get())
            assertEquals(1, archives.get())
        }
    }

    @Test
    fun repeatedTouches_duringFade_doNotQueueMoreDestinations() {
        val starts = AtomicInteger()
        val archives = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MainScreen(
                onStart = { starts.incrementAndGet() },
                onArchive = { archives.incrementAndGet() },
                animationsEnabled = false,
            )
        }

        compose.onNodeWithText("시작하기").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("시작하기").performTouchInput { click(); click() }
        compose.onNodeWithText("이야기 보관함").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(1, starts.get())
            assertEquals(0, archives.get())
        }
    }

    @Test
    fun pendingDestination_survivesStateRestoration_withoutDuplicateNavigation() {
        val archives = AtomicInteger()
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MainScreen(onStart = {}, onArchive = { archives.incrementAndGet() }, animationsEnabled = false)
        }

        compose.onNodeWithText("이야기 보관함").performClick()
        compose.mainClock.advanceTimeBy(150)
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, archives.get()) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, archives.get()) }
    }

    @Test
    fun portraitAndShortLandscape_keepBothButtonsInsideTheWindow() {
        val windowSize = mutableStateOf(DpSize(360.dp, 640.dp))
        compose.setContent {
            // Fit both logical window sizes inside the Pleos instrumentation host.
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                Box(Modifier.requiredSize(windowSize.value.width, windowSize.value.height).testTag("viewport")) {
                    MainScreen(onStart = {}, onArchive = {}, animationsEnabled = false, modifier = Modifier.fillMaxSize())
                }
            }
        }

        assertButtonsFit()
        for (size in listOf(DpSize(640.dp, 320.dp), DpSize(540.dp, 300.dp), DpSize(500.dp, 300.dp))) {
            compose.runOnIdle { windowSize.value = size }
            assertButtonsFit()
        }
    }

    @Test
    fun largeSystemFont_keepsButtonLabelsUnclipped() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 2f)) {
                Box(Modifier.requiredSize(360.dp, 640.dp).testTag("viewport")) {
                    MainScreen(onStart = {}, onArchive = {}, animationsEnabled = false, modifier = Modifier.fillMaxSize())
                }
            }
        }

        assertButtonsFit()
        for (label in listOf("시작하기", "이야기 보관함")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
            assertTrue("$label needs text layout information", layouts.isNotEmpty())
            val details = layouts.joinToString { layout ->
                "size=${layout.size}, style=${layout.layoutInput.style}, constraints=${layout.layoutInput.constraints}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                    "lineCount=${layout.lineCount}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                    "intrinsicWidth=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}, " +
                    "density=${layout.layoutInput.density.density}, fontScale=${layout.layoutInput.density.fontScale}, " +
                    "glyphs=${label.indices.map { layout.getBoundingBox(it) }}, " +
                    "lineEnds=${(0 until layout.lineCount).map { layout.getLineEnd(it, visibleEnd = true) }}, " +
                    "ellipsized=${(0 until layout.lineCount).map { layout.isLineEllipsized(it) }}"
            }
            assertTrue("$label must not clip at 200% font size: $details", layouts.none { it.hasVisualOverflow })
        }
    }

    @Test
    fun animatedCharacters_moveVisiblyAndReverseVerticalDirection() {
        val bodies = listOf(
            R.drawable.character_green,
            R.drawable.character_purple,
            R.drawable.character_pink,
            R.drawable.character_yellow,
            R.drawable.character_blue,
        )
        val traces = bodies.associateWith { mutableListOf<Float>() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                Box(Modifier.requiredSize(850.dp, 592.dp)) {
                    MainScreen(onStart = {}, onArchive = {}, animationsEnabled = true)
                }
            }
        }

        fun recordPositions() {
            for (body in bodies) {
                val bounds = compose.onNodeWithTag("character-$body").getUnclippedBoundsInRoot()
                traces.getValue(body).add((bounds.top.value + bounds.bottom.value) / 2)
            }
        }

        compose.mainClock.advanceTimeByFrame()
        recordPositions()
        repeat(25) {
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            recordPositions()
        }

        for ((body, positions) in traces) {
            val travel = positions.maxOrNull()!! - positions.minOrNull()!!
            val changes = positions.zipWithNext { before, after -> after - before }
            assertTrue("Character $body should visibly float, observed ${travel}dp: $positions", travel >= 10f)
            assertTrue("Character $body should move both up and down: $positions", changes.any { it > 0.5f } && changes.any { it < -0.5f })
        }
    }

    private fun assertButtonsFit() {
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val buttons = listOf("시작하기", "이야기 보관함").map { label ->
            val button = compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue("$label must fit horizontally", bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue("$label must fit vertically", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue("$label needs a 48dp touch target", bounds.bottom - bounds.top >= 48.dp)
            bounds
        }
        val (start, archive) = buttons
        assertTrue("Buttons must not overlap", start.right <= archive.left || archive.right <= start.left || start.bottom <= archive.top || archive.bottom <= start.top)
    }
}
