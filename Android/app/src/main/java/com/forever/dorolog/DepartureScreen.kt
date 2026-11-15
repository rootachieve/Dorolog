package com.forever.dorolog

import android.car.Car
import android.Manifest
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private val QuestionFont = FontFamily(Font(R.font.ownglyph_yuntaeng))
private val DepartureFont = FontFamily(
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)

@Composable
internal fun DepartureRoute(
    tripId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
    car: Car? = null,
    summaryAnimationsEnabled: Boolean = animationsEnabled,
    summaryBrowsingAllowed: Boolean = true,
    summaryRepository: TripSummaryRepository? = null,
    onTripFinished: (String) -> Unit = {},
    viewModel: DepartureViewModel? = null,
    initialDestination: Destination? = null,
) = key(tripId) {
    require(tripId.isNotBlank())
    val context = LocalContext.current
    val owner = remember(tripId) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel ?: androidx.lifecycle.viewmodel.compose.viewModel<DepartureViewModel>(
        viewModelStoreOwner = owner,
        factory = viewModelFactory { initializer {
            DepartureViewModel(tripId = tripId, initialDestination = initialDestination,
                repository = if (BuildConfig.TRIP_API_BASE_URL.isBlank()) DepartureRepository()
                    else DepartureRepository.remote(BuildConfig.TRIP_API_BASE_URL),
                tripRepository = if (BuildConfig.TRIP_API_BASE_URL.isBlank()) TripRepository()
                    else TripRepository.remote(BuildConfig.TRIP_API_BASE_URL))
        } },
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    require(uiState.tripId == tripId)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val drivingViewModel: DrivingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = owner,
        factory = viewModelFactory { initializer { DrivingViewModel(DrivingAudioSession(context.applicationContext)) } },
    )
    val drivingState by drivingViewModel.uiState.collectAsStateWithLifecycle()
    val source = remember(vm, drivingViewModel) {
        DepartureVehicleSource(context.applicationContext, vm::onNavigationChanged,
            vm::onSpeedChanged, onArrived = vm::onDestinationArrived,
            onSafety = drivingViewModel::onSafetyChanged,
            onParked = vm::onParkedChanged,
            onLocation = drivingViewModel::onLocationChanged,
            onDestinationSet = vm::onDestinationSet,
            onDestinationConfirmed = vm::onDestinationConfirmed)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (lifecycle.currentState == Lifecycle.State.RESUMED) {
            source.start(car)
        }
    }
    var permissionsRequested by remember { mutableStateOf(false) }
    LaunchedEffect(car, uiState.phase) {
        if (!permissionsRequested) {
            permissionsRequested = true
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.READ_PHONE_STATE)
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                    add(Car.PERMISSION_SPEED)
                    add(Car.PERMISSION_POWERTRAIN)
                    add("pleos.car.permission.CAR_SAFETY")
                    add("pleos.car.permission.CAR_DOORS")
                }
            }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (permissions.isNotEmpty()) permission.launch(permissions.toTypedArray())
        }
    }
    LifecycleResumeEffect(car, source) {
        vm.setActive(true)
        drivingViewModel.setForeground(true)
        source.start(car)
        onPauseOrDispose { vm.setActive(false); drivingViewModel.setForeground(false); source.stop() }
    }
    DisposableEffect(source) {
        onDispose { source.stop(); drivingViewModel.setForeground(false) }
    }
    LaunchedEffect(uiState.phase, uiState.finishingTrip) {
        if (uiState.phase == DepartureViewModel.Phase.Talk && !uiState.finishingTrip) {
            uiState.topics.firstOrNull { it.id == uiState.selectedTopicId }?.let {
                drivingViewModel.start(it, uiState.destination, uiState.tripId)
            }
        } else if (uiState.phase != DepartureViewModel.Phase.Morphing) drivingViewModel.finish()
        if (uiState.phase == DepartureViewModel.Phase.Summary) onTripFinished(uiState.tripId)
    }
    val morph = remember { Animatable(if (uiState.phase == DepartureViewModel.Phase.Talk) 1f else 0f) }
    LaunchedEffect(uiState.phase, lifecycle, animationsEnabled) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val target = if (uiState.phase == DepartureViewModel.Phase.Ready) 0f else 1f
            if (animationsEnabled) morph.animateTo(target, tween(700, easing = FastOutSlowInEasing))
            else morph.snapTo(target)
            if (uiState.phase == DepartureViewModel.Phase.Morphing && lifecycle.currentState == Lifecycle.State.RESUMED) {
                vm.onMorphFinished()
            }
        }
    }
    val back: () -> Unit = {
        if (uiState.finishingTrip) { vm.leave(); onBack() }
        else when (uiState.phase) {
            DepartureViewModel.Phase.Ready, DepartureViewModel.Phase.Arrived,
            DepartureViewModel.Phase.Skipping -> { vm.leave(); onBack() }
            DepartureViewModel.Phase.Talk -> drivingViewModel.pause()
            DepartureViewModel.Phase.Morphing -> Unit
            DepartureViewModel.Phase.Summary -> { vm.leave(); onBack() }
        }
    }
    val finishTrip = { drivingViewModel.finish(); vm.finishTrip() }
    BackHandler(onBack = back)
    if (uiState.finishingTrip) {
        FinishTripScreen(uiState.finishError, finishTrip, back, modifier)
    } else when (uiState.phase) {
        DepartureViewModel.Phase.Arrived -> ParkedScreen(uiState, vm::toggleStory,
            vm::saveStories, vm::toggleSkip, finishTrip, vm::retryStories, modifier)
        DepartureViewModel.Phase.Skipping -> SkippedRecordingScreen(uiState.destination,
            vm::toggleSkip, finishTrip, modifier)
        DepartureViewModel.Phase.Summary -> TripSummaryScreen(uiState.tripId, back, modifier,
            destinationName = uiState.destination?.name, animationsEnabled = summaryAnimationsEnabled,
            browsingAllowed = summaryBrowsingAllowed, repository = summaryRepository)
        DepartureViewModel.Phase.Talk -> TalkScreen(uiState, drivingState,
            drivingViewModel::pause, drivingViewModel::resume,
            onFinish = finishTrip, modifier = modifier)
        else -> DepartureScreen(uiState = uiState, onBack = back, onSelectTopic = vm::selectTopic,
            onPrevious = { vm.moveTopic(-1) }, onNext = { vm.moveTopic(1) },
            onRetry = vm::retry, modifier = modifier, morphProgress = morph.value,
            animationsEnabled = animationsEnabled)
    }
    if (uiState.arrivalDialogPending) AlertDialog(
        onDismissRequest = vm::dismissArrivalDialog,
        title = { Text("다음 목적지에 도착했어요") },
        text = { Text("다음 목적지까지 기록 멈추기 설정이 해제되었어요") },
        confirmButton = { TextButton(onClick = vm::dismissArrivalDialog) { Text("확인") } },
    )

}

@Composable
fun DepartureScreen(
    uiState: DepartureViewModel.UiState,
    onBack: () -> Unit,
    onSelectTopic: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    morphProgress: Float = if (uiState.phase == DepartureViewModel.Phase.Ready) 0f else 1f,
    animationsEnabled: Boolean = true,
    amplitudes: List<Int>? = null,
) {
    val progress = morphProgress.coerceIn(0f, 1f)
    val highContrast = rememberHighContrast()
    val ink = if (highContrast) Color.Black else lerp(Color.White, Color.Black, progress)
    val base = lerp(Color(0xFF7774FF), Color(0xFFF8FAFE), progress)
    BoxWithConstraints(modifier.fillMaxSize().clip(RoundedCornerShape(40.dp))
        .background(base)
        .background(Brush.verticalGradient(
            0f to Color.Transparent,
            (0.56731f * progress) to Color.Transparent,
            1f to Color.White.copy(alpha = 0.63f),
        ))
        .border(1.dp, Color(0x33131417), RoundedCornerShape(40.dp))
        .testTag(if (uiState.phase == DepartureViewModel.Phase.Talk) "conversation-content" else "departure-screen")) {
        val width = maxWidth
        val height = maxHeight
        val scale = minOf(width.value / 1700f, height.value / 1184f)
        val density = LocalDensity.current
        val ready = uiState.phase == DepartureViewModel.Phase.Ready
        val selected = uiState.topics.indexOfFirst { it.id == uiState.selectedTopicId }.coerceAtLeast(0)
        var headerPixels by remember { mutableIntStateOf(0) }
        val headerHeight = with(density) { headerPixels.toDp() }
        val arrowSize = (126 * scale).dp.coerceAtLeast(48.dp)
        val side = (76 * scale).dp.coerceAtLeast(12.dp)
        val questionHeight = (320 * scale).dp.coerceAtLeast((144 * density.fontScale).dp)
        val centerY = height * (0.375f + (0.32f - 0.375f) * progress)
        val questionTop = maxOf(headerHeight + 12.dp, centerY - questionHeight / 2).coerceAtMost(height)
        val waveformTop = maxOf(height * (631f / 1184f) - (67 * scale).dp,
            headerHeight + questionHeight + 24.dp)
            .coerceAtMost((height - (134 * scale).dp - 16.dp).coerceAtLeast(0.dp))
        val questionBottom = minOf(
            questionTop + questionHeight,
            height - (404 * scale).dp - 16.dp,
            if (progress > 0.5f) waveformTop - 12.dp else height,
        )
        val availableQuestionHeight = (questionBottom - questionTop).coerceAtLeast(0.dp)
        val questionWidth = if (uiState.phase == DepartureViewModel.Phase.Talk) (width - side * 2).coerceAtLeast(0.dp)
            else (width - (side + arrowSize + 12.dp) * 2).coerceAtLeast(width * 0.5f)

        QuestionCharacter(progress, scale, Modifier.fillMaxSize().testTag("topic-character"))

        if (progress > 0f && uiState.topics.isNotEmpty()) {
            val waveModifier = Modifier.align(Alignment.TopCenter).offset(y = waveformTop)
                .width((812 * scale).dp).height((134 * scale).dp).testTag("conversation-wave")
            if (amplitudes == null) {
                ConversationWave(scale, progress, waveModifier)
            } else {
                LiveConversationWave(amplitudes, scale, waveModifier)
            }
        }

        Column(Modifier.fillMaxWidth()
            .onSizeChanged { headerPixels = it.height }
            .padding(horizontal = (64 * scale).dp.coerceAtLeast(16.dp),
                vertical = (43 * scale).dp.coerceAtLeast(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(66.dp))
                    .background(lerp(Color.White, Color(0xFF7C79FF), progress))
                    .padding(horizontal = (24 * scale).dp.coerceAtLeast(16.dp), vertical = (16 * scale).dp.coerceAtLeast(8.dp))) {
                    Text(stringResource(if (progress < 0.5f) R.string.departure else R.string.driving),
                        color = if (highContrast) Color.Black
                            else lerp(Color(0xFF7C79FF), Color.White, progress),
                        fontFamily = DepartureFont, fontWeight = FontWeight.SemiBold,
                        fontSize = (32 * scale).coerceAtLeast(18f).sp)
                }
                Spacer(Modifier.weight(1f))
                if (width >= 700.dp && uiState.phase != DepartureViewModel.Phase.Talk) {
                    RecommendationHint(uiState, scale, highContrast, Modifier.weight(3f).graphicsLayer { alpha = 1f - progress })
                    Spacer(Modifier.weight(1f))
                }
                if (uiState.phase != DepartureViewModel.Phase.Talk) {
                    TextButton(onClick = onBack, enabled = uiState.phase != DepartureViewModel.Phase.Morphing,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.back), color = ink,
                            fontFamily = DepartureFont, fontSize = (22 * scale).coerceAtLeast(18f).sp)
                    }
                }
            }
            if (width < 700.dp && uiState.phase != DepartureViewModel.Phase.Talk) {
                RecommendationHint(uiState, scale, highContrast, Modifier.align(Alignment.CenterHorizontally).graphicsLayer { alpha = 1f - progress })
            }
        }

        Box(Modifier.align(Alignment.TopCenter).offset(y = questionTop)
            .width(questionWidth).height(availableQuestionHeight)) {
            when {
                uiState.loading -> StatusMessage(stringResource(R.string.loading_recommendations), ink)
                uiState.contentError -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.recommendations_unavailable), color = ink, fontFamily = DepartureFont, fontSize = 22.sp)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = ink, fontSize = 20.sp) }
                }
                uiState.topics.isEmpty() -> StatusMessage(stringResource(R.string.recommendations_empty), ink)
                else -> QuestionPager(uiState, selected, scale, ink, onSelectTopic, animationsEnabled)
            }
        }
        if (!uiState.loading && !uiState.contentError && uiState.topics.size > 1 && uiState.phase != DepartureViewModel.Phase.Talk) {
            TopicArrow(R.drawable.topic_previous, R.string.previous_topic,
                ready && selected > 0, highContrast, onPrevious,
                Modifier.offset(side, height * (545.5f / 1184f) - arrowSize / 2)
                    .size(arrowSize).graphicsLayer { alpha = 1f - progress })
            TopicArrow(R.drawable.topic_next, R.string.next_topic,
                ready && selected < uiState.topics.lastIndex, highContrast, onNext,
                Modifier.offset(width - side - arrowSize, height * (545.5f / 1184f) - arrowSize / 2)
                    .size(arrowSize).graphicsLayer { alpha = 1f - progress })
        }
    }
}

@Composable
private fun QuestionPager(
    state: DepartureViewModel.UiState, selected: Int, scale: Float, ink: Color,
    onSelect: (String) -> Unit, animationsEnabled: Boolean,
) {
    val pager = rememberPagerState(initialPage = selected, pageCount = { state.topics.size })
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager, state.topics, selected, state.phase) {
        if (pager.currentPage != selected ||
            (state.phase != DepartureViewModel.Phase.Ready && pager.currentPageOffsetFraction != 0f)) {
            if (animationsEnabled && state.phase == DepartureViewModel.Phase.Ready) pager.animateScrollToPage(selected)
            else pager.scrollToPage(selected)
        }
    }
    LaunchedEffect(pager, state.topics) {
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }
            .filter { !it.second }.map { it.first }.distinctUntilChanged()
            .collect { page -> state.topics.getOrNull(page)?.let { select(it.id) } }
    }
    HorizontalPager(state = pager, key = { state.topics[it].id },
        userScrollEnabled = state.phase == DepartureViewModel.Phase.Ready,
        modifier = Modifier.fillMaxSize().testTag("recommended-topics")) { page ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("“" + state.topics[page].question + "”", color = ink, textAlign = TextAlign.Center,
                fontFamily = QuestionFont,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp,
                    maxFontSize = (100 * scale).coerceAtLeast(24f).sp),
                lineHeight = ((135 * scale).coerceAtLeast(34f) / (100 * scale).coerceAtLeast(24f)).em)
        }
    }
}

@Composable
private fun RecommendationHint(state: DepartureViewModel.UiState, scale: Float, highContrast: Boolean, modifier: Modifier) {
    val text = when {
        state.countdownSeconds != null -> stringResource(R.string.departure_countdown, state.countdownSeconds)
        state.motionAvailable == false -> stringResource(R.string.motion_unavailable)
        else -> if (state.hasBrowsedTopics) return else stringResource(R.string.recommendation_tip)
    }
    Box(modifier.clip(RoundedCornerShape(40.dp)).background(Color.White.copy(alpha = 0.2f))
        .padding(horizontal = 18.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (highContrast) Color(0xFF28215C) else Color.White,
            fontFamily = DepartureFont, fontWeight = FontWeight.SemiBold,
            fontSize = (28 * scale).coerceAtLeast(14f).sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TopicArrow(resource: Int, description: Int, enabled: Boolean, highContrast: Boolean, onClick: () -> Unit, modifier: Modifier) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Image(painterResource(resource), contentDescription = stringResource(description),
            colorFilter = if (highContrast) ColorFilter.tint(Color(0xFF28215C)) else null,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (enabled) 1f else 0.35f })
    }
}

@Composable
private fun rememberHighContrast(): Boolean {
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    if (Build.VERSION.SDK_INT < 34) return largeFont
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var contrast by remember(manager) { mutableFloatStateOf(manager?.contrast ?: 0f) }
    DisposableEffect(manager) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        manager?.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { manager?.removeContrastChangeListener(listener) }
    }
    return largeFont || contrast > 0f
}

@Composable
private fun BoxScope.StatusMessage(text: String, ink: Color) {
    Text(text, color = ink, fontFamily = DepartureFont, fontSize = 22.sp,
        textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
}

@Composable
private fun QuestionCharacter(progress: Float, scale: Float, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val diameter = (902.774f * scale).dp
        Image(painterResource(R.drawable.topic_character), contentDescription = null,
            modifier = Modifier.offset(maxWidth / 2 - diameter / 2, maxHeight + (47 * scale).dp - diameter / 2)
                .size(diameter).graphicsLayer { rotationZ = 11.62f })
        Canvas(Modifier.fillMaxSize()) {
            val factor = scale * density
            val avatar = Offset(size.width / 2, size.height + 47 * factor)
            fun eye(from: Offset, to: Offset, dimensions: Size, color: Color) {
                val center = avatar + Offset(
                    from.x + (to.x - from.x) * progress,
                    from.y + (to.y - from.y) * progress,
                ) * factor
                val extent = Size(dimensions.width * factor, dimensions.height * factor)
                rotate(-11.62f + 9.12f * progress, center) {
                    drawRoundRect(color, center - Offset(extent.width / 2, extent.height / 2),
                        extent, CornerRadius(101 * factor))
                }
            }
            eye(Offset(-105.23f, -268.18f), Offset(-73.04f, -250.99f), Size(148.923f, 197.709f), Color(0xFFF7F8FA))
            eye(Offset(40.77f, -289.18f), Offset(74.45f, -248.59f), Size(148.923f, 197.709f), Color(0xFFF7F8FA))
            eye(Offset(-101.67f, -312.27f), Offset(-73.12f, -255.71f), Size(82.543f, 109.074f), Color(0xFF131417))
            eye(Offset(44.67f, -333.27f), Offset(73.88f, -259.71f), Size(82.543f, 109.074f), Color(0xFF131417))
        }
    }
}

@Composable
private fun ConversationWave(scale: Float, progress: Float, modifier: Modifier) {
    Canvas(modifier.graphicsLayer { alpha = progress }) {
        val factor = scale * density
        val center = Offset(size.width / 2, size.height / 2)
        val color = Color(0xFF7E7BFF)
        drawLine(color, center - Offset(406 * factor, 0f), center + Offset(406 * factor, 0f),
            strokeWidth = 8 * factor, cap = StrokeCap.Round)
        listOf(-39.5f to 52f, -24.5f to 72f, -9.5f to 106f, 6f to 134f,
            21.5f to 106f, 36.5f to 72f, 50.5f to 52f).forEach { (x, height) ->
            val start = center + Offset(x * factor, -height / 2 * factor)
            drawLine(color, start, start + Offset(0f, height * factor),
                strokeWidth = 7 * factor, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun LiveConversationWave(amplitudes: List<Int>, scale: Float, modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val factor = scale * density
        val color = Color(0xFF7E7BFF)
        val baseline = 8 * factor
        drawRoundRect(color, Offset(0f, (size.height - baseline) / 2),
            Size(size.width, baseline), CornerRadius(baseline / 2))
        val spacing = size.width / amplitudes.size.coerceAtLeast(1)
        val barWidth = minOf(7 * factor, spacing * 0.8f)
        amplitudes.forEachIndexed { index, amplitude ->
            // A fixed 0..100 scale keeps quiet input small even in an otherwise quiet window.
            val height = maxOf(baseline, size.height * amplitude.coerceIn(0, 100) / 100f)
            drawRoundRect(color, Offset(index * spacing + (spacing - barWidth) / 2, (size.height - height) / 2),
                Size(barWidth, height), CornerRadius(4 * factor))
        }
    }
}

@Composable
internal fun TalkScreen(
    uiState: DepartureViewModel.UiState,
    drivingState: DrivingViewModel.UiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val display = uiState.copy(topics = listOf(RecommendedTopic("current", drivingState.question)),
        selectedTopicId = "current", loading = false, contentError = false)
    val status = drivingState.status ?: if (drivingState.waitingForAudio) stringResource(R.string.audio_waiting) else null
    BoxWithConstraints(modifier.fillMaxSize().clip(RoundedCornerShape(40.dp)).testTag("talk-screen")) {
        val scale = minOf(maxWidth.value / 1700f, maxHeight.value / 1184f)
        DepartureScreen(display, {}, {}, {}, {}, {},
            Modifier.fillMaxSize().then(if (drivingState.paused) {
                Modifier.blur((5.35f * scale).dp).clearAndSetSemantics {}
            } else Modifier),
            morphProgress = 1f, animationsEnabled = false, amplitudes = drivingState.amplitudes)
        if (drivingState.paused) {
            Box(Modifier.fillMaxSize().background(
                if (drivingState.resumeEnabled) Color(0x91181641) else Color(0x9168678D)))
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)
                .padding(bottom = (109 * scale).dp.coerceAtLeast(16.dp)),
                horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Column(Modifier.heightIn(max = maxHeight).widthIn(min = 144.dp).clip(RoundedCornerShape(20.dp))
                        .clickable(enabled = drivingState.resumeEnabled, role = Role.Button,
                            onClickLabel = stringResource(R.string.resume_conversation), onClick = onResume)
                        .testTag("resume-conversation").padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        BoxWithConstraints(Modifier.weight(269f, fill = false).heightIn(max = (269 * scale).dp)
                            .width((251 * scale).dp), contentAlignment = Alignment.Center) {
                            val iconScale = minOf(scale, maxHeight.value / 269f, maxWidth.value / 251f)
                            if (drivingState.resumeEnabled) {
                                Image(painterResource(R.drawable.driving_resume), contentDescription = null,
                                    modifier = Modifier.size((251 * iconScale).dp, (269 * iconScale).dp))
                            } else {
                                Row(Modifier.size((232 * iconScale).dp, (269 * iconScale).dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    repeat(2) { Box(Modifier.fillMaxHeight().width((57 * iconScale).dp)
                                        .clip(RoundedCornerShape(94.dp)).background(Color(0xFFF9FBFE))) }
                                }
                            }
                        }
                        Spacer(Modifier.weight(70f, fill = false).height((70 * scale).dp.coerceAtLeast(16.dp)))
                        Text(stringResource(if (drivingState.resumeEnabled) R.string.resume_conversation else R.string.conversation_paused),
                            color = Color.White, fontFamily = DepartureFont, fontWeight = FontWeight.SemiBold,
                            fontSize = (64 * scale).coerceAtLeast(24f).sp)
                    }
                }
                status?.let {
                    Text(it, color = Color.White, fontFamily = DepartureFont, textAlign = TextAlign.Center,
                        fontSize = 16.sp, modifier = Modifier.testTag("driving-status"))
                    Spacer(Modifier.height(8.dp))
                }
                Text(stringResource(R.string.recording_paused_hint), color = ForeverColors.pausedHintContent,
                    fontFamily = DepartureFont, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    fontSize = (40 * scale).coerceAtLeast(16f).sp,
                    modifier = Modifier.clip(RoundedCornerShape(66.dp)).background(ForeverColors.pausedHintBackground)
                        .border((5 * scale).dp.coerceAtLeast(1.dp), ForeverColors.pausedHintOutline, RoundedCornerShape(66.dp))
                        .padding(horizontal = (64 * scale).dp.coerceAtLeast(16.dp), vertical = (24 * scale).dp.coerceAtLeast(10.dp))
                        .testTag("recording-paused-tooltip").semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height((48 * scale).dp.coerceAtLeast(16.dp)))
                Button(onClick = onFinish, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                    border = androidx.compose.foundation.BorderStroke((5 * scale).dp, Color.White.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(66.dp),
                    modifier = Modifier.widthIn(min = (276 * scale).dp.coerceAtLeast(144.dp))
                        .heightIn(min = 48.dp).testTag("finish-conversation"),
                    contentPadding = PaddingValues(horizontal = (24 * scale).dp, vertical = (16 * scale).dp)) {
                    Image(painterResource(R.drawable.driving_close), contentDescription = null,
                        modifier = Modifier.size((49 * scale).dp.coerceAtLeast(20.dp)))
                    Text(stringResource(R.string.finish_conversation), color = Color.White,
                        fontFamily = DepartureFont, fontWeight = FontWeight.SemiBold,
                        fontSize = (40 * scale).coerceAtLeast(20f).sp)
                }
            }
        } else {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().fillMaxHeight(0.72f)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.pause_conversation), onClick = onPause)
                .testTag("pause-conversation"))
        }
        if (!drivingState.paused) status?.let {
            Text(it, color = Color(0xFF575760),
                fontFamily = DepartureFont, textAlign = TextAlign.Center, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                    .testTag("driving-status"))
        }
    }
}

@Composable
private fun SkippedRecordingScreen(destination: Destination?, onResume: () -> Unit, onFinish: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().clip(RoundedCornerShape(40.dp)).background(Color(0xFFF8FAFE))
        .padding(32.dp).testTag("skipped-recording-screen"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        Text("다음 목적지까지 기록을 쉬고 있어요", fontFamily = DepartureFont, fontSize = 28.sp, textAlign = TextAlign.Center)
        destination?.let { Text(it.name, fontFamily = DepartureFont, fontSize = 24.sp) }
        Text("도착하면 기록 멈추기가 자동으로 해제돼요", fontFamily = DepartureFont, fontSize = 20.sp)
        Button(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) { Text("기록 다시 시작") }
        TextButton(onClick = onFinish) { Text("여행 종료하기") }
    }
}

@Composable
private fun FinishTripScreen(error: String?, onRetry: () -> Unit, onHome: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().clip(RoundedCornerShape(40.dp)).background(Color(0xFFF8FAFE))
        .padding(32.dp).testTag("trip-finishing-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        if (error == null) {
            CircularProgressIndicator()
            Text("여행을 마무리하고 있어요", fontFamily = DepartureFont, fontSize = 24.sp)
        } else {
            Text(error, fontFamily = DepartureFont, fontSize = 24.sp, textAlign = TextAlign.Center)
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시도") }
        }
        TextButton(onClick = onHome, modifier = Modifier.heightIn(min = 48.dp)) { Text("홈으로") }
    }
}

@Preview(name = "추천 질문", widthDp = 850, heightDp = 592)
@Preview(name = "추천 질문 세로", widthDp = 360, heightDp = 640)
@Composable
private fun DeparturePreview() {
    val topics = sampleDepartureResponse.toModel()
    DepartureScreen(DepartureViewModel.UiState(topics = topics, selectedTopicId = topics.first().id, loading = false),
        {}, {}, {}, {}, {})
}

@Preview(name = "주행 중", widthDp = 850, heightDp = 592)
@Composable
private fun TalkPreview() {
    val topics = sampleDepartureResponse.toModel()
    TalkScreen(DepartureViewModel.UiState(topics = topics, selectedTopicId = topics.first().id,
        loading = false, phase = DepartureViewModel.Phase.Talk),
        DrivingViewModel.UiState(question = topics.first().question), {}, {}, {})
}
