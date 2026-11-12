package com.forever.dorolog

import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.ui.test.captureToImage
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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun entryVideo_runsAtDoubleSpeed_andLoopsPastTheEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prepared = CountDownLatch(1)
        val player = createIntroPlayer(instrumentation.targetContext)
        try {
            player.setOnPreparedListener { prepared.countDown() }
            player.prepareAsync()
            assertTrue("Video must decode", prepared.await(10, TimeUnit.SECONDS))
            player.start()
            compose.waitUntil(timeoutMillis = 10_000) { player.currentPosition >= 500 }
            val startPosition = player.currentPosition
            val startTime = SystemClock.elapsedRealtime()
            SystemClock.sleep(1_200)
            val rate = (player.currentPosition - startPosition).toFloat() / (SystemClock.elapsedRealtime() - startTime)
            assertEquals("Playback must run at 2x", 2f, rate, 0.35f)
            SystemClock.sleep(15_000)
            assertTrue("Video must keep playing after its 15 second loop", player.isPlaying)
            assertTrue("Playback must have wrapped to the beginning", player.currentPosition < 8_000)
        } finally {
            player.release()
        }
    }

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
        for (label in listOf("시작하기")) {
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
        val archive = compose.onNodeWithText("이야기 보관함").getUnclippedBoundsInRoot()
        val label = compose.onNodeWithTag("archive-label", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Archive lettering must fit at 200% font size", label.left >= archive.left && label.right <= archive.right && label.bottom <= archive.bottom)
    }

    @Test
    fun road_reachesTheBottom_whenTheWindowIsResized() {
        val windowSize = mutableStateOf(DpSize(360.dp, 640.dp))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                Box(Modifier.requiredSize(windowSize.value.width, windowSize.value.height).testTag("viewport")) {
                    MainScreen(onStart = {}, onArchive = {}, animationsEnabled = false)
                }
            }
        }
        for (size in listOf(DpSize(360.dp, 640.dp), DpSize(850.dp, 592.dp), DpSize(640.dp, 320.dp))) {
            compose.runOnIdle { windowSize.value = size }
            val screenshot = compose.onNodeWithTag("viewport").captureToImage()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "entry-${size.width.value.toInt()}x${size.height.value.toInt()}.png")
                .outputStream().use { screenshot.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            val scene = compose.onNodeWithTag("entry-scene").assertIsDisplayed()
            val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
            val bounds = scene.getUnclippedBoundsInRoot()
            assertEquals(viewport.left.value, bounds.left.value, 1f)
            assertEquals(viewport.right.value, bounds.right.value, 1f)
            // At 0.5 density, rounding can extend the scene by one physical pixel.
            assertEquals("Road must extend to the bottom", viewport.bottom.value, bounds.bottom.value, 2f)
            val video = compose.onNodeWithTag("entry-video").getUnclippedBoundsInRoot()
            val start = compose.onNodeWithText("시작하기").getUnclippedBoundsInRoot()
            assertEquals("Video must preserve its cropped aspect ratio", 728f / 1668f,
                (video.bottom - video.top).value / (video.right - video.left).value, 0.01f)
            // Every character in the cropped clip stays within its upper half.
            assertTrue("Start button must sit below the characters", start.top >= video.top + (video.bottom - video.top) / 2)
            val pixels = scene.captureToImage().toPixelMap()
            for (x in listOf(1, pixels.width / 2, pixels.width - 2)) {
                val color = pixels[x, pixels.height - 2]
                assertTrue("Bottom must remain the pastel road, not empty space: $color", color.red > 0.8f && color.blue > 0.8f && color.green < 0.94f)
            }
            assertButtonsFit()
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
