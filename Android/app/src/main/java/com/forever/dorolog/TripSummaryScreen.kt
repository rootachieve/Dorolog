package com.forever.dorolog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SummaryFont = FontFamily(
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)

@Composable
fun TripSummaryScreen(
    tripId: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    destinationName: String? = null,
    animationsEnabled: Boolean = true,
    browsingAllowed: Boolean = true,
    onBack: (() -> Unit)? = null,
    viewModel: TripSummaryViewModel? = null,
    repository: TripSummaryRepository? = null,
) {
    key(tripId) {
        require(tripId.isNotBlank())
        BackHandler(onBack = onBack ?: onDone)
        if (!browsingAllowed) {
            RestrictedTripSummary(onDone, onBack, modifier)
        } else {
            val owner = remember(tripId) {
                object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            }
            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
            val vm = viewModel ?: androidx.lifecycle.viewmodel.compose.viewModel<TripSummaryViewModel>(
                viewModelStoreOwner = owner,
                factory = viewModelFactory { initializer {
                    TripSummaryViewModel(tripId, repository ?: if (BuildConfig.TRIP_API_BASE_URL.isBlank()) TripSummaryRepository()
                        else TripSummaryRepository.remote(BuildConfig.TRIP_API_BASE_URL))
                } },
            )
            val uiState by vm.uiState.collectAsStateWithLifecycle()
            require(uiState.tripId == tripId)
            TripSummaryContent(uiState, vm::selectStory, vm::clearSelection, vm::retry,
                onDone, modifier, destinationName, animationsEnabled, onBack)
        }
    }
}

@Composable
private fun RestrictedTripSummary(onDone: () -> Unit, onBack: (() -> Unit)?, modifier: Modifier) {
    Column(modifier.fillMaxSize().clip(RoundedCornerShape(40.dp))
        .background(ForeverColors.summaryBackground)
        .border(1.dp, ForeverColors.summaryOutline, RoundedCornerShape(40.dp))
        .padding(24.dp).testTag("settlement-screen"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("안전한 곳에 정차한 후 여행 결산을 확인해 주세요", color = ForeverColors.summaryInk,
            fontFamily = SummaryFont, fontSize = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("‹ 뒤로", color = ForeverColors.summaryAccent, fontFamily = SummaryFont, fontSize = 20.sp)
            }
            Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ForeverColors.summaryAccent,
                    contentColor = ForeverColors.summarySurface)) {
                Text("확인", fontFamily = SummaryFont, fontSize = 20.sp)
            }
        }
    }
}

@Composable
internal fun TripSummaryContent(
    uiState: TripSummaryViewModel.UiState,
    onSelectStory: (String) -> Unit,
    onClearSelection: (String) -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    destinationName: String? = null,
    animationsEnabled: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize().clip(RoundedCornerShape(40.dp))
        .background(ForeverColors.summaryBackground)
        .border(1.dp, ForeverColors.summaryOutline, RoundedCornerShape(40.dp))
        .testTag("settlement-screen")) {
        val scale = minOf(maxWidth.value / 1700f, maxHeight.value / 1184f)
        val side = (64 * scale).dp.coerceAtLeast(16.dp)
        val buttonBottom = (85 * scale).dp.coerceAtLeast(16.dp)
        val buttonHeight = (97 * scale).dp.coerceAtLeast(48.dp)
        val narrow = maxWidth < 600.dp

        Column(Modifier.fillMaxSize().padding(top = (44 * scale).dp.coerceAtLeast(16.dp),
            bottom = buttonBottom + buttonHeight + 20.dp)) {
            Row(Modifier.padding(start = side), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("‹ 뒤로", color = ForeverColors.summaryAccent, fontFamily = SummaryFont,
                        fontSize = maxOf(24 * scale, 16f).sp)
                }
                Text("목적지 도착", Modifier
                .clip(RoundedCornerShape(66.dp)).background(ForeverColors.summaryBadge)
                .padding(horizontal = (24 * scale).dp.coerceAtLeast(12.dp),
                    vertical = (16 * scale).dp.coerceAtLeast(8.dp)),
                color = ForeverColors.summarySurface, fontFamily = SummaryFont, fontWeight = FontWeight.SemiBold,
                fontSize = maxOf(32 * scale, 14f).sp)
            }
            Spacer(Modifier.height((38 * scale).dp.coerceAtLeast(16.dp)))
            val heading = when {
                uiState.loading && uiState.stories.isEmpty() -> "이번 여행의 이야기를 불러오고 있어요."
                uiState.error != null && uiState.stories.isEmpty() -> "이야기를 불러오지 못했어요."
                uiState.stories.isEmpty() -> "이번 여행에서 저장한 이야기가 없어요."
                else -> "${destinationName?.takeIf { it.isNotBlank() }?.let { "${it}가는 길" } ?: "이번 여행"}, ${uiState.stories.size}개의 이야기를 저장했어요."
            }
            Text(heading, Modifier.fillMaxWidth().padding(horizontal = side),
                color = ForeverColors.summaryInk, fontFamily = SummaryFont, fontWeight = FontWeight.SemiBold,
                fontSize = maxOf(40 * scale, 18f).sp, textAlign = TextAlign.Center)

            if (uiState.stories.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    if (uiState.loading) CircularProgressIndicator(color = ForeverColors.summaryAccent)
                    else if (uiState.error != null) SummaryError(uiState.error, onRetry)
                }
            } else {
                Spacer(Modifier.height((91 * scale).dp.coerceAtLeast(24.dp)))
                SummaryStories(uiState, scale, side.value, narrow, animationsEnabled,
                    onSelectStory, onClearSelection, Modifier.weight(1f))
                if (uiState.error != null) SummaryError(uiState.error, onRetry)
            }
        }
        Button(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter)
            .padding(bottom = buttonBottom).height(buttonHeight), shape = RoundedCornerShape(65.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ForeverColors.summaryAccent,
                contentColor = ForeverColors.summarySurface),
            contentPadding = PaddingValues(horizontal = (80 * scale).dp.coerceAtLeast(32.dp))) {
            Text("확인", fontFamily = SummaryFont, fontWeight = FontWeight.Medium,
                fontSize = maxOf(48 * scale, 20f).sp)
        }
    }
}

@Composable
private fun SummaryError(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = ForeverColors.summaryBody, fontFamily = SummaryFont,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("다시 불러오기", color = ForeverColors.summaryAccent, fontFamily = SummaryFont)
        }
    }
}

@Composable
private fun SummaryStories(
    uiState: TripSummaryViewModel.UiState,
    scale: Float,
    side: Float,
    narrow: Boolean,
    animationsEnabled: Boolean,
    onSelectStory: (String) -> Unit,
    onClearSelection: (String) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth.value
        val shortWindow = maxHeight < 300.dp
        val density = LocalDensity.current
        val scroll = rememberScrollState()
        val centers = remember(width, uiState.stories.size) {
            summaryStoryCenters(uiState.stories.size, width, (width * 191f / 1700f).coerceAtLeast(32f))
        }
        val step = centers.getOrNull(1)?.minus(centers.first()) ?: width
        val labelWidth = minOf(step, (240 * scale).coerceAtLeast(64f))
        val nodeSize = (86 * scale).dp.coerceAtLeast(32.dp)
        val selectedIndex = uiState.stories.indexOfFirst { it.key == uiState.selectedStoryKey }
        val offset = with(density) { scroll.value.toDp().value }
        val selectedCenter = centers.getOrNull(selectedIndex)?.minus(offset)
        val selected = uiState.stories.getOrNull(selectedIndex)
        val currentClearSelection by rememberUpdatedState(onClearSelection)

        LaunchedEffect(selected?.key, selectedIndex, centers, width, side, scroll, density) {
            val story = selected ?: return@LaunchedEffect
            val center = centers[selectedIndex]
            snapshotFlow { center - with(density) { scroll.value.toDp().value } }
                .collect { position ->
                    if (!summarySelectionVisible(position, side, width - side)) currentClearSelection(story.key)
                }
        }

        Column(if (shortWindow) Modifier.fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height((167 * scale).dp.coerceAtLeast(92.dp))
                .horizontalScroll(scroll).testTag("summary-rail")) {
                val railWidth = maxOf(width, centers.last() + centers.first())
                Box(Modifier.width(railWidth.dp).fillMaxHeight()) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (centers.size > 1) drawLine(ForeverColors.summaryRail,
                            Offset(centers.first().dp.toPx(), nodeSize.toPx() / 2),
                            Offset(centers.last().dp.toPx(), nodeSize.toPx() / 2),
                            strokeWidth = (15 * scale).dp.toPx())
                    }
                    uiState.stories.forEachIndexed { index, story ->
                        val active = story.key == selected?.key
                        Column(Modifier.offset(x = (centers[index] - labelWidth / 2).dp)
                            .width(labelWidth.dp).fillMaxHeight()
                            .selectable(active, role = Role.Button, onClick = { onSelectStory(story.key) })
                            .semantics { contentDescription = "${index + 1}번 이야기, ${story.region}, ${story.title}" },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(nodeSize).clip(CircleShape)
                                .background(if (active) ForeverColors.summaryAccent else ForeverColors.summarySurface)
                                .border((9 * scale).dp.coerceAtLeast(2.dp), ForeverColors.summaryRail, CircleShape),
                                contentAlignment = Alignment.Center) {
                                Text("${index + 1}", color = if (active) ForeverColors.summarySurface else ForeverColors.summaryMuted,
                                    fontFamily = SummaryFont, fontWeight = FontWeight.SemiBold,
                                    fontSize = maxOf(32 * scale, 14f).sp)
                            }
                            Spacer(Modifier.height((17 * scale).dp.coerceAtLeast(6.dp)))
                            Text(story.region, color = ForeverColors.summaryMuted,
                                fontFamily = SummaryFont, fontSize = maxOf(24 * scale, 12f).sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height((7 * scale).dp.coerceAtLeast(3.dp)))
                            Text(story.title, color = if (active) ForeverColors.summaryAccent else ForeverColors.summaryTitle,
                                fontFamily = SummaryFont, fontWeight = FontWeight.SemiBold,
                                fontSize = maxOf(24 * scale, 12f).sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (selected != null && selectedCenter != null &&
                summarySelectionVisible(selectedCenter, side, width - side)) {
                Spacer(Modifier.height((25 * scale).dp.coerceAtLeast(8.dp)))
                val tailWidth = (39 * scale).dp
                Image(painterResource(R.drawable.trip_summary_tail), contentDescription = null,
                    modifier = Modifier.offset(x = selectedCenter.dp - tailWidth / 2)
                        .size(tailWidth, (41 * scale).dp))
                key(selected.key) {
                    SummaryDetail(selected, scale, narrow, animationsEnabled,
                        Modifier.padding(horizontal = side.dp).fillMaxWidth()
                            .then(if (shortWindow) Modifier else Modifier.weight(1f, fill = false)))
                }
            }
        }
    }
}

@Composable
private fun SummaryDetail(
    story: SavedTripStory,
    scale: Float,
    narrow: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier,
) {
    val content: @Composable () -> Unit = {
        Column {
            Text(story.title, color = ForeverColors.summaryInk, fontFamily = SummaryFont,
                fontWeight = FontWeight.SemiBold, fontSize = maxOf(36 * scale, 18f).sp)
            Spacer(Modifier.height((12 * scale).dp.coerceAtLeast(6.dp)))
            val time = story.occurredAt?.atZone(ZoneId.systemDefault())
                ?.format(DateTimeFormatter.ofPattern("HH시mm분", Locale.KOREAN)) ?: "시간 정보 없음"
            Text("$time · ${story.region}", color = ForeverColors.summaryMuted,
                fontFamily = SummaryFont, fontSize = maxOf(24 * scale, 12f).sp)
            Spacer(Modifier.height((24 * scale).dp.coerceAtLeast(12.dp)))
            Text(story.content ?: "이야기 내용이 아직 준비되지 않았어요.",
                color = ForeverColors.summaryBody, fontFamily = SummaryFont,
                fontSize = maxOf(28 * scale, 14f).sp, lineHeight = maxOf(33.6f * scale, 19f).sp)
        }
    }
    val quotes: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((27 * scale).dp.coerceAtLeast(10.dp))) {
            if (story.conversationExcerpts.isEmpty()) Text("핵심 대화 내용이 아직 준비되지 않았어요.",
                color = ForeverColors.summaryBody, fontFamily = SummaryFont,
                fontSize = maxOf(24 * scale, 12f).sp)
            story.conversationExcerpts.take(3).forEachIndexed { index, text ->
                val appearance = remember { Animatable(if (animationsEnabled) 0f else 1f) }
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(animationsEnabled, lifecycle) {
                    if (!animationsEnabled) appearance.snapTo(1f)
                    else lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        if (appearance.value < 1f) appearance.animateTo(1f, tween(380, delayMillis = 180 + index * 220))
                    }
                }
                val color = when (index) {
                    0 -> ForeverColors.summaryFirstQuote
                    1 -> ForeverColors.summarySecondQuote
                    else -> ForeverColors.summaryThirdQuote
                }
                Text("“$text”", Modifier.align(if (index == 1) Alignment.End else Alignment.Start)
                    .padding(start = if (index == 1) 0.dp else (56 * scale).dp,
                        end = if (index == 1) (27 * scale).dp else 0.dp)
                    .graphicsLayer { alpha = appearance.value; translationY = (1 - appearance.value) * 24.dp.toPx() }
                    .clip(RoundedCornerShape((24 * scale).dp)).background(color)
                    .padding(horizontal = (20 * scale).dp.coerceAtLeast(10.dp), vertical = (16 * scale).dp.coerceAtLeast(8.dp)),
                    color = ForeverColors.summaryInk, fontFamily = SummaryFont, fontWeight = FontWeight.SemiBold,
                    fontSize = maxOf(24 * scale, 12f).sp)
            }
        }
    }
    Box(modifier.height((372 * scale).dp.coerceAtLeast(192.dp))
        .clip(RoundedCornerShape((36 * scale).dp.coerceAtLeast(16.dp)))
        .background(ForeverColors.summarySurface).testTag("summary-detail")) {
        if (narrow) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) { content(); quotes() }
        } else {
            Row(Modifier.verticalScroll(rememberScrollState())
                .padding((49 * scale).dp.coerceAtLeast(20.dp)),
                horizontalArrangement = Arrangement.spacedBy((24 * scale).dp),
                verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(881f)) { content() }
                Box(Modifier.weight(574f).padding(top = (18 * scale).dp, bottom = (23 * scale).dp)) { quotes() }
            }
        }
    }
}

internal fun summaryStoryCenters(count: Int, width: Float, edge: Float): List<Float> = when {
    count <= 0 -> emptyList()
    count == 1 -> listOf(width / 2)
    else -> List(count) { edge + it * (width - 2 * edge) / (minOf(count, 5) - 1) }
}

internal fun summarySelectionVisible(center: Float, left: Float, right: Float): Boolean = center in left..right

@Preview(name = "결산 · 4개", widthDp = 850, heightDp = 592)
@Preview(name = "결산 · 세로", widthDp = 360, heightDp = 640)
@Composable
private fun TripSummaryPreview() {
    val stories = (1..4).map { index ->
        SavedTripStory("$index", "$index", "이야기 제목", "지역",
            Instant.parse("2026-11-13T01:00:00Z"),
            "이야기 내용이에요. 어떤 이야기를 나눴는지 요약이 되어있어요. 간략하게 요약하고 오른쪽에는 핵심 대화내용이 1개~3개 노출되는 형식이에요",
            listOf("너 그때 그랬었잖아.", "몰라. 나 기억 안나", "정말 웃긴다."))
    }
    TripSummaryContent(TripSummaryViewModel.UiState("preview", stories, loading = false, selectedStoryKey = "2"),
        {}, {}, {}, {}, destinationName = "서울", animationsEnabled = false)
}
