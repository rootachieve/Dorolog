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
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DorologAppTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher
    private lateinit var selectionViewModel: TripSelectionViewModel

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
        compose.runOnUiThread { selectionViewModel = TripSelectionViewModel(selectionRepository()) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DorologApp(onBackground = {}, animationsEnabled = false, tripSelectionViewModel = selectionViewModel)
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
    fun finishedTripWaitsForResumeAcknowledgement_beforeEnteringQuestions() {
        val response = CompletableDeferred<OngoingTripDto>()
        val requests = CopyOnWriteArrayList<String>()
        setAppContent(selectionRepository(status = "finished") { id ->
            requests += id
            response.await()
        })
        compose.onNodeWithText("시작하기").assertIsDisplayed().assertIsEnabled()
        openScreen("시작하기", "trip-selection-screen")
        compose.onNodeWithText("새로 시작").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("resume-selected-trip").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
        compose.onNodeWithTag("resume-selected-trip").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("목적지 없이 시작").assertDoesNotExist()
        compose.onNodeWithTag("trip-selection-screen").assertIsDisplayed()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("selected-trip", requests.single())
            response.complete(selectionTrip("selected-trip"))
        }
        compose.mainClock.advanceTimeBy(500)
        waitForNode(hasTestTag("departure-screen"))
        assertQuestionsReady()
        compose.onNodeWithTag("trip-selection-screen").assertDoesNotExist()
        compose.onNodeWithText("selected-trip").assertDoesNotExist()
    }

    @Test
    fun activeTripEntersQuestions_withoutResumeRequest() {
        val resumes = AtomicInteger()
        setAppContent(selectionRepository {
            resumes.incrementAndGet()
            error("active trip must enter questions without resume")
        })
        openScreen("시작하기", "trip-selection-screen")
        compose.onNodeWithTag("resume-selected-trip").performClick()
        compose.mainClock.advanceTimeBy(500)
        waitForNode(hasTestTag("departure-screen"))
        assertQuestionsReady()
        compose.runOnIdle { assertEquals(0, resumes.get()) }
    }

    @Test
    fun newTripStartsCreatedServerId_onlyAfterNoDestinationConfirmation() {
        val creates = AtomicInteger()
        val requests = CopyOnWriteArrayList<CreateTripRequestDto>()
        setAppContent(TripSelectionRepository(
            loadTrips = { OngoingTripsResponseDto(emptyList()) },
            createTrip = { creates.incrementAndGet(); requests += it; selectionTrip("new-server-trip").copy(destinationName = null,
                destinationLatitude = null, destinationLongitude = null) },
        ))
        openScreen("시작하기", "trip-selection-screen")
        seedSelectionContext()
        compose.onNodeWithText("새로 시작").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("목적지 없이 시작").assertIsDisplayed()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, creates.get())
            assertEquals(emptyList<CreateTripRequestDto>(), requests.toList())
        }
        seedSelectionContext()
        compose.onNodeWithText("목적지 없이 시작").performClick()
        compose.mainClock.advanceTimeBy(500)
        waitForNode(hasTestTag("departure-screen"))
        compose.runOnIdle { assertEquals(1, creates.get()); assertNull(requests.single().destinationName) }
    }

    @Test
    fun recreatingCompositionDuringStartFade_staysOnReadyHome() {
        val instance = mutableStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            key(instance.value) {
                DorologApp(onBackground = {}, animationsEnabled = false)
            }
        }
        compose.onNodeWithText("시작하기").performClick()
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle { instance.value++ }
        compose.mainClock.advanceTimeBy(1_000)
        assertMainReady()
    }

    @Test
    fun returningHomeThenReselectingSameTrip_createsFreshQuestions() {
        val injectedSelection = mutableStateOf<TripSelectionViewModel?>(null)
        compose.runOnUiThread {
            selectionViewModel = TripSelectionViewModel(selectionRepository())
            injectedSelection.value = selectionViewModel
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DorologApp(onBackground = {}, animationsEnabled = false, tripSelectionViewModel = injectedSelection.value)
        }
        openScreen("시작하기", "departure-screen")
        compose.onNodeWithContentDescription("다음 추천 질문").performClick()
        compose.mainClock.advanceTimeBy(500)
        waitForNode(hasText("지난 여행에서 가장 기억에 남는 순간은 언제였나요?", substring = true))
        compose.onNodeWithText("뒤로가기").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertMainReady()
        compose.runOnIdle {
            selectionViewModel = TripSelectionViewModel(selectionRepository())
            injectedSelection.value = selectionViewModel
        }
        openScreen("시작하기", "trip-selection-screen")
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
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

    @Test fun archiveMapDetailsStayInline_andBothBackActionsReturnOverviewWithoutLoadingSummary() {
        val archiveRequests = CopyOnWriteArrayList<ArchiveSort>()
        val summaryRequests = CopyOnWriteArrayList<String>()
        setArchiveApp(archiveRequests, summaryRequests)
        openScreen("이야기 보관함", "archive-screen")
        showOldestBusanTrips()
        val lastStory = "archive-map-story-server-story-12"
        repeat(2) { pass ->
            compose.onNodeWithTag("archive-region-trips").performScrollToNode(hasTestTag(lastStory))
            compose.onNodeWithTag(lastStory).assertIsDisplayed()
            compose.onNodeWithText("여행 12").assertIsDisplayed()
            compose.onNodeWithText("목록으로").assertIsDisplayed()
            compose.onNodeWithText("오래된순").assertDoesNotExist()
            compose.onNodeWithTag("settlement-screen").assertDoesNotExist()
            compose.onNodeWithTag("archive-map-story-server-story-14").assertDoesNotExist()
            if (pass == 0) compose.onNodeWithText("목록으로").performClick()
            else compose.runOnIdle { backDispatcher.onBackPressed() }
            compose.onNodeWithTag("archive-region-trips").assertIsDisplayed()
            compose.onNodeWithText("오래된순").assertIsDisplayed()
            compose.onNodeWithText("목록으로").assertDoesNotExist()
            compose.onNodeWithTag("archive-map-story-server-story-1").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(listOf(ArchiveSort.Latest), archiveRequests.toList())
                assertEquals(emptyList<String>(), summaryRequests.toList())
            }
            if (pass == 0) compose.onNodeWithTag("archive-map-story-server-story-1").performClick()
        }
    }

    @Test fun archiveTripSummaryConfirmationReturnsHome_andNextArchiveEntryHasFreshStateAndRequest() {
        val archiveRequests = CopyOnWriteArrayList<ArchiveSort>()
        val summaryRequests = CopyOnWriteArrayList<String>()
        setArchiveApp(archiveRequests, summaryRequests)
        openScreen("이야기 보관함", "archive-screen")
        compose.onNodeWithText("최신순").performClick()
        compose.onNodeWithText("오래된순").performClick()
        compose.onNodeWithTag("archive-trip-server-trip-1").performClick()
        compose.onNodeWithTag("settlement-screen").assertIsDisplayed()
        compose.onNodeWithText("확인").performClick()
        assertMainReady()
        openScreen("이야기 보관함", "archive-screen")
        compose.onNodeWithTag("archive-trip-grid").assertIsDisplayed()
        compose.onNodeWithText("최신순").assertIsDisplayed()
        compose.onNodeWithText("목록으로").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(ArchiveSort.Latest, ArchiveSort.Latest), archiveRequests.toList())
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

    private fun archiveRepository(requests: MutableList<ArchiveSort>): ArchiveRepository {
        val trips = (1..14).map { index ->
            val busan = index <= 12
            ArchiveTripDto(id = "server-trip-$index", originName = "서울",
                destinationName = if (busan) "부산 목적지 $index" else "서울 목적지 $index",
                destinationLatitude = if (busan) 35.16 else 37.56,
                destinationLongitude = if (busan) 129.16 else 126.98,
                status = "finished", startedAt = "2026-11-${index.toString().padStart(2, '0')}T00:00:00Z",
                endedAt = null, storyCount = 1)
        }
        val stories = trips.mapIndexed { index, trip ->
            ArchiveMapStoryDto("server-story-${index + 1}", trip.id, "여행 ${index + 1}",
                if (index < 12) "부산" else "서울", trip.destinationLatitude, trip.destinationLongitude,
                "가족과 나눈 대화", emptyList(), trip.startedAt)
        }
        return ArchiveRepository({ requests += ArchiveSort.Latest; ArchiveResponseDto(trips.reversed()) },
            { ArchiveMapResponseDto(14, 14,
                listOf(ArchiveRegionDto("부산", 12, 35.16, 129.16), ArchiveRegionDto("서울", 2, 37.56, 126.98)), stories) })
    }

    private fun summaryRepository(requests: MutableList<String>) = TripSummaryRepository { tripId ->
        requests += tripId
        TripSummaryResponseDto(tripId, listOf(TripSummaryStoryDto("saved", "저장된 이야기", "부산",
            summary = "가족과 나눈 대화")), listOf("saved"))
    }

    private fun selectionRepository(
        status: String = "active",
        resumeTrip: suspend (String) -> OngoingTripDto = { selectionTrip(it) },
    ) = TripSelectionRepository(
        loadTrips = { OngoingTripsResponseDto(listOf(selectionTrip("selected-trip").copy(status = status))) },
        createTrip = { selectionTrip("new-server-trip") },
        resumeTrip = resumeTrip,
    )

    private fun selectionTrip(id: String) = OngoingTripDto(id = id, originName = "서울",
        originLatitude = 37.56, originLongitude = 126.98, destinationName = "부산",
        destinationLatitude = 35.16, destinationLongitude = 129.16, status = "active",
        startedAt = "2026-11-13T00:00:00Z", storyCount = 2)

    private fun setAppContent(
        repository: TripSelectionRepository = selectionRepository(),
        onBackground: () -> Unit = {},
    ) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { selectionViewModel = TripSelectionViewModel(repository) }
        compose.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DorologApp(onBackground = onBackground, animationsEnabled = false, tripSelectionViewModel = selectionViewModel)
        }
    }

    private fun openScreen(button: String, screen: String) {
        waitForNode(hasText(button) and isEnabled()).performClick()
        compose.mainClock.advanceTimeBy(600)
        if (button == "시작하기" && screen == "departure-screen") startSelectedTrip()
        waitForNode(hasTestTag(screen))
        compose.onNodeWithText("시작하기").assertDoesNotExist()
        compose.onNodeWithText(if (screen == "archive-screen") "메인 화면으로" else "뒤로가기").assertIsDisplayed()
    }

    private fun startSelectedTrip() {
        waitForNode(hasTestTag("resume-selected-trip") and isEnabled()).performClick()
        compose.mainClock.advanceTimeBy(500)
        waitForNode(hasTestTag("departure-screen"))
        assertQuestionsReady()
    }

    private fun assertQuestionsReady() {
        waitForNode(hasText("이번 여행에서 가족이랑 꼭 같이", substring = true))
        compose.onNodeWithContentDescription("다음 추천 질문").assertIsDisplayed().assertIsEnabled()
    }

    private fun waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // SDK callbacks use real time while these tests advance Compose frames manually.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        return compose.onNode(matcher).assertIsDisplayed()
    }

    private fun seedSelectionContext() {
        compose.runOnIdle {
            selectionViewModel.onNavigationChanged(null, true)
            selectionViewModel.onLocationChanged(VehicleLocation("서울", 37.56, 126.98))
        }
    }

    private fun assertMainReady() {
        waitForNode(hasText("시작하기") and isEnabled())
        compose.onNodeWithText("이야기 보관함").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("trip-selection-screen").assertDoesNotExist()
        compose.onNodeWithTag("departure-screen").assertDoesNotExist()
        compose.onNodeWithTag("archive-screen").assertDoesNotExist()
        compose.onNodeWithTag("settlement-screen").assertDoesNotExist()
    }

    private val destinations = listOf("시작하기" to "trip-selection-screen", "시작하기" to "departure-screen",
        "이야기 보관함" to "archive-screen")
}
