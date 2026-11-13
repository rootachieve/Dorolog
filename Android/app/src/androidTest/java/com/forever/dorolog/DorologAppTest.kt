package com.forever.dorolog

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
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
            compose.onNodeWithText(if (screen == "archive-screen") "메인 화면으로" else "뒤로가기")
                .assertIsEnabled().performClick()
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
    fun savedStateRestoration_alwaysStartsOnHome() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DorologApp(onBackground = {}, animationsEnabled = false, selectedTripId = "selected-trip")
        }

        for ((button, screen) in destinations) {
            openScreen(button, screen)
            compose.mainClock.autoAdvance = true
            restoration.emulateSavedInstanceStateRestore()
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeByFrame()
            assertMainReady()
        }
    }

    @Test
    fun homeStartAlwaysOpensSelection_andOnlyChosenTripCanEnterRecording() {
        val selectedTripId = mutableStateOf<String?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DorologApp(onBackground = {}, animationsEnabled = false, selectedTripId = selectedTripId.value)
        }
        compose.onNodeWithText("시작하기").assertIsDisplayed().assertIsEnabled()
        openScreen("시작하기", "trip-selection-screen")
        compose.onNodeWithText("선택한 여행으로 시작").assertIsNotEnabled()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
        compose.runOnIdle { selectedTripId.value = " " }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("선택한 여행으로 시작").assertIsNotEnabled()
        compose.runOnIdle { selectedTripId.value = "chosen-server-trip" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("선택한 여행으로 시작").assertIsEnabled()
        compose.onNodeWithTag("trip-selection-screen").assertIsDisplayed()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
        startSelectedTrip()
        compose.onNodeWithText("chosen-server-trip").assertDoesNotExist()
    }

    @Test
    fun recreatingCompositionDuringStartFade_staysOnReadyHome() {
        val instance = mutableStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            key(instance.value) {
                DorologApp(onBackground = {}, animationsEnabled = false, selectedTripId = "selected-trip")
            }
        }
        compose.onNodeWithText("시작하기").performClick()
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle { instance.value++ }
        compose.mainClock.advanceTimeBy(1_000)
        assertMainReady()
    }

    @Test
    fun changingHomeSelection_doesNotRetargetActiveJourney_andReturningHomeCreatesFreshUi() {
        val selectedTripId = mutableStateOf<String?>("chosen-server-trip")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DorologApp(onBackground = {}, animationsEnabled = false, selectedTripId = selectedTripId.value)
        }
        openScreen("시작하기", "departure-screen")
        compose.onNodeWithContentDescription("다음 추천 질문").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("지난 여행에서 가장 기억에 남는 순간은 언제였나요?", substring = true).assertIsDisplayed()
        compose.runOnIdle { selectedTripId.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("departure-screen").assertIsDisplayed()
        compose.onNodeWithText("지난 여행에서 가장 기억에 남는 순간은 언제였나요?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("뒤로가기").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertMainReady()
        openScreen("시작하기", "trip-selection-screen")
        compose.onNodeWithText("선택한 여행으로 시작").assertIsNotEnabled()
        compose.runOnIdle { selectedTripId.value = "chosen-server-trip" }
        compose.mainClock.advanceTimeByFrame()
        startSelectedTrip()
        compose.onNodeWithText("이번 여행에서 가족이랑 꼭 같이", substring = true).assertIsDisplayed()
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
    fun physicalBack_duringWhiteFade_doesNotBackgroundOrCancelTripSelection() {
        val backgrounds = AtomicInteger()
        setAppContent { backgrounds.incrementAndGet() }

        compose.onNodeWithText("시작하기").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(500)

        compose.runOnIdle { assertEquals(0, backgrounds.get()) }
        compose.onNodeWithTag("trip-selection-screen").assertIsDisplayed()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
    }

    @Test fun archiveSummaryRequestsChosenId_andBothBackActionsRetainMapFilterSortAndScroll() {
        val archiveRequests = CopyOnWriteArrayList<ArchiveSort>()
        val summaryRequests = CopyOnWriteArrayList<String>()
        setArchiveApp(archiveRequests, summaryRequests)
        openScreen("이야기 보관함", "archive-screen")
        showOldestBusanTrips()
        val lastTrip = "archive-region-trip-server-trip-12"
        compose.onNodeWithTag("archive-region-trips").performScrollToNode(hasTestTag(lastTrip))
        repeat(2) { pass ->
            compose.onNodeWithTag(lastTrip).assertIsDisplayed().performClick()
            compose.onNodeWithTag("settlement-screen").assertIsDisplayed()
            compose.onNodeWithContentDescription("1번 이야기, 부산, 저장된 이야기").assertIsDisplayed()
            compose.runOnIdle { assertEquals(List(pass + 1) { "server-trip-12" }, summaryRequests.toList()) }
            if (pass == 0) compose.onNodeWithText("‹ 뒤로").performClick()
            else compose.runOnIdle { backDispatcher.onBackPressed() }
            compose.onNodeWithTag("archive-region-trips").assertIsDisplayed()
            compose.onNodeWithText("오래된순").assertIsDisplayed()
            compose.onNodeWithText("부산").assertIsDisplayed()
            compose.onNodeWithText("전체 여행").assertIsDisplayed()
            compose.onNodeWithTag(lastTrip).assertIsDisplayed()
            compose.onNodeWithTag("archive-region-trip-server-trip-14").assertDoesNotExist()
            compose.runOnIdle { assertEquals(listOf(ArchiveSort.Latest, ArchiveSort.Oldest), archiveRequests.toList()) }
        }
    }

    @Test fun archiveSummaryConfirmationReturnsHome_andNextArchiveEntryHasFreshStateAndRequest() {
        val archiveRequests = CopyOnWriteArrayList<ArchiveSort>()
        val summaryRequests = CopyOnWriteArrayList<String>()
        setArchiveApp(archiveRequests, summaryRequests)
        openScreen("이야기 보관함", "archive-screen")
        showOldestBusanTrips()
        compose.onNodeWithTag("archive-region-trip-server-trip-1").performClick()
        compose.onNodeWithTag("settlement-screen").assertIsDisplayed()
        compose.onNodeWithText("확인").performClick()
        assertMainReady()
        openScreen("이야기 보관함", "archive-screen")
        compose.onNodeWithTag("archive-trip-grid").assertIsDisplayed()
        compose.onNodeWithText("최신순").assertIsDisplayed()
        compose.onNodeWithText("전체 여행").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(ArchiveSort.Latest, ArchiveSort.Oldest, ArchiveSort.Latest), archiveRequests.toList())
            assertEquals(listOf("server-trip-1"), summaryRequests.toList())
        }
    }

    @Test fun summaryBecomingRestrictedThroughAppHidesMetadata_andKeepsArchiveBackAndHomeActions() {
        val browsingAllowed = mutableStateOf(true)
        val archiveRequests = CopyOnWriteArrayList<ArchiveSort>()
        val summaryRequests = CopyOnWriteArrayList<String>()
        val archiveRepository = archiveRepository(archiveRequests)
        val summaryRepository = summaryRepository(summaryRequests)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DorologApp(onBackground = {}, animationsEnabled = false, archiveBrowsingAllowed = browsingAllowed.value,
                archiveRepository = archiveRepository, summaryRepository = summaryRepository)
        }
        openScreen("이야기 보관함", "archive-screen")
        compose.onNodeWithTag("archive-trip-server-trip-14").performClick()
        compose.onNodeWithContentDescription("1번 이야기, 부산, 저장된 이야기").assertIsDisplayed()
        compose.runOnIdle { browsingAllowed.value = false }
        compose.onNodeWithText("안전한 곳에 정차한 후 여행 결산을 확인해 주세요").assertIsDisplayed()
        compose.onNodeWithTag("summary-rail").assertDoesNotExist()
        compose.onNodeWithText("저장된 이야기", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("server-trip-14"), summaryRequests.toList()) }
        compose.onNodeWithText("‹ 뒤로").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("홈으로").assertIsDisplayed().assertIsEnabled().performClick()
        assertMainReady()
    }

    private fun setArchiveApp(archiveRequests: MutableList<ArchiveSort>, summaryRequests: MutableList<String>) {
        val archiveRepository = archiveRepository(archiveRequests)
        val summaryRepository = summaryRepository(summaryRequests)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DorologApp(onBackground = {}, animationsEnabled = false,
                archiveRepository = archiveRepository, summaryRepository = summaryRepository)
        }
    }

    private fun showOldestBusanTrips() {
        compose.onNodeWithText("미리보기 데이터 · 서버 미연결").assertDoesNotExist()
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("최신순").performClick()
        compose.onNodeWithText("오래된순").performClick()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("부산 12").performClick()
        compose.onNodeWithTag("archive-region-trips").assertIsDisplayed()
    }

    private fun archiveRepository(requests: MutableList<ArchiveSort>) = ArchiveRepository({ sort ->
        requests += sort
        val trips = (1..14).map { index ->
            val busan = index <= 12
            ArchiveTripDto("server-trip-$index", "여행 $index", "서울", listOf(ArchiveDestinationDto(
                if (busan) "부산 목적지 $index" else "서울 목적지 $index",
                if (busan) 35.16 else 37.56, if (busan) 129.16 else 126.98,
                if (busan) "26000" else "11000", if (busan) "부산" else "서울")),
                "2026-11-${index.toString().padStart(2, '0')}", 1)
        }
        ArchiveResponseDto(if (sort == ArchiveSort.Latest) trips.reversed() else trips)
    }, false)

    private fun summaryRepository(requests: MutableList<String>) = TripSummaryRepository { tripId ->
        requests += tripId
        TripSummaryResponseDto(tripId, listOf(TripSummaryStoryDto("saved", "저장된 이야기", "부산",
            content = "가족과 나눈 대화")), listOf("saved"))
    }

    private fun setAppContent(onBackground: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DorologApp(onBackground = onBackground, animationsEnabled = false, selectedTripId = "selected-trip")
        }
    }

    private fun openScreen(button: String, screen: String) {
        compose.onNodeWithText(button).assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(600)
        if (button == "시작하기" && screen == "departure-screen") startSelectedTrip()
        compose.onNodeWithTag(screen).assertIsDisplayed()
        compose.onNodeWithText("시작하기").assertDoesNotExist()
        compose.onNodeWithText(if (screen == "archive-screen") "메인 화면으로" else "뒤로가기").assertIsDisplayed()
    }

    private fun startSelectedTrip() {
        compose.onNodeWithText("선택한 여행으로 시작").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("departure-screen").assertIsDisplayed()
    }

    private fun assertMainReady() {
        compose.onNodeWithText("시작하기").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("이야기 보관함").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("trip-selection-screen").assertDoesNotExist()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
        compose.onNodeWithTag("archive-screen").assertDoesNotExist()
        compose.onNodeWithTag("settlement-screen").assertDoesNotExist()
    }

    private val destinations = listOf("시작하기" to "trip-selection-screen", "시작하기" to "departure-screen",
        "이야기 보관함" to "archive-screen")
}
