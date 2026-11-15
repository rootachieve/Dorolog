package com.forever.dorolog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ParkedFont = FontFamily(
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)
private val ParkedBackground = Color(0xFFF8FAFE)
private val ParkedButton = Color(0xFFEBF0FF)

@Composable
internal fun ParkedScreen(
    uiState: DepartureViewModel.UiState,
    onToggleStory: (String) -> Unit,
    onSave: () -> Unit,
    onToggleSkip: () -> Unit,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().clip(RoundedCornerShape(40.dp))
        .background(ParkedBackground)
        .border(1.dp, Color(0x33131417), RoundedCornerShape(40.dp))
        .testTag("parked-screen")) {
        val scale = minOf(maxWidth.value / 1700f, maxHeight.value / 1184f)
        val compact = maxWidth < 650.dp
        val side = (64 * scale).dp.coerceAtLeast(16.dp)
        val gap = (20 * scale).dp.coerceAtLeast(10.dp)
        val gradientHeight = (255 * scale).dp.coerceAtLeast(64.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = side, vertical = (43 * scale).dp.coerceAtLeast(16.dp))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                Text("주차 중", color = Color.White, fontFamily = ParkedFont,
                    fontWeight = FontWeight.SemiBold, fontSize = (32 * scale).coerceAtLeast(18f).sp,
                    modifier = Modifier.clip(RoundedCornerShape(66.dp)).background(Color(0xFF7C79FF))
                        .padding(horizontal = (24 * scale).dp.coerceAtLeast(12.dp), vertical = 10.dp))
                Text("남길 이야기 고르기", color = Color.Black, fontFamily = ParkedFont,
                    fontWeight = FontWeight.SemiBold, fontSize = (48 * scale).coerceAtLeast(22f).sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry, enabled = !uiState.storiesLoading && !uiState.savingStories) {
                    Text("새로고침", fontFamily = ParkedFont)
                }
            }
            Text("다시 주행하면 기록 모드로 돌아가요. 목적지를 바꿨다면 새 주제를 골라요.",
                color = Color(0xFF646464), fontFamily = ParkedFont,
                fontSize = (28 * scale).coerceAtLeast(16f).sp, lineHeight = (39 * scale).coerceAtLeast(22f).sp,
                modifier = Modifier.padding(top = 10.dp, bottom = (70 * scale).dp.coerceAtLeast(16.dp),
                    start = if (compact) 0.dp else (166 * scale).dp))

            Box(Modifier.weight(1f).fillMaxWidth()
                .padding(horizontal = if (compact) 0.dp else (160 * scale).dp)) {
                when {
                    uiState.storiesLoading && uiState.stories.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).semantics { contentDescription = "이야기 불러오는 중" })
                    uiState.stories.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.storiesError ?: "이번 구간에 기록된 이야기가 없어요", fontFamily = ParkedFont,
                            color = Color(0xFF646464), fontSize = 20.sp, textAlign = TextAlign.Center)
                        if (uiState.storiesError != null) TextButton(onClick = onRetry) { Text("다시 불러오기", fontFamily = ParkedFont) }
                    }
                    else -> {
                        val scroll = rememberScrollState()
                        val scope = rememberCoroutineScope()
                        Column(Modifier.fillMaxSize().padding(end = 36.dp).verticalScroll(scroll)
                            .testTag("parked-stories"), verticalArrangement = Arrangement.spacedBy(gap)) {
                            uiState.stories.forEachIndexed { index, story ->
                                key(story.id) {
                                    val selected = story.id in uiState.selectedStoryIds
                                    Row(Modifier.fillMaxWidth().heightIn(min = (167 * scale).dp.coerceAtLeast(96.dp))
                                        .clip(RoundedCornerShape((36 * scale).dp.coerceAtLeast(18.dp)))
                                        .background(if (selected) ParkedButton else Color.White)
                                        .border(if (selected) (5 * scale).dp.coerceAtLeast(2.dp) else 0.dp,
                                            if (selected) Color(0xFFB6C1FF) else Color.Transparent,
                                            RoundedCornerShape((36 * scale).dp.coerceAtLeast(18.dp)))
                                        .toggleable(selected, enabled = uiState.canSelectStories && !uiState.savingStories,
                                            role = Role.Checkbox, onValueChange = { onToggleStory(story.id) })
                                        .testTag("parked-story-${story.id}")
                                        .padding((44 * scale).dp.coerceAtLeast(16.dp)),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy((23 * scale).dp.coerceAtLeast(14.dp))) {
                                        Box(Modifier.size((64 * scale).dp.coerceAtLeast(40.dp)).background(Color.Black, CircleShape),
                                            contentAlignment = Alignment.Center) {
                                            Text("${uiState.stories.size - index}", color = Color.White, fontFamily = ParkedFont,
                                                fontSize = (32 * scale).coerceAtLeast(18f).sp)
                                        }
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(story.title, color = Color.Black, fontFamily = ParkedFont,
                                                fontWeight = FontWeight.SemiBold, fontSize = (32 * scale).coerceAtLeast(20f).sp)
                                            Text(story.region, color = Color(0xFF646464), fontFamily = ParkedFont,
                                                fontSize = (24 * scale).coerceAtLeast(16f).sp)
                                        }
                                    }
                                }
                            }
                            // The final story must scroll completely above the gradient.
                            Spacer(Modifier.height(gradientHeight + 24.dp))
                        }
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(gradientHeight)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.White))))
                        if (scroll.maxValue in 1 until Int.MAX_VALUE && scroll.viewportSize > 0) {
                            val density = LocalDensity.current
                            val viewport = scroll.viewportSize.toFloat()
                            val thumb = (viewport * viewport / (viewport + scroll.maxValue)).coerceAtLeast(with(density) { 48.dp.toPx() })
                                .coerceAtMost(viewport)
                            val travel = (viewport - thumb).coerceAtLeast(1f)
                            val fraction = scroll.value.toFloat() / scroll.maxValue
                            Box(Modifier.align(Alignment.CenterEnd).width(48.dp).fillMaxHeight()
                                .testTag("parked-scrollbar")
                                .semantics {
                                    contentDescription = "이야기 목록 스크롤"
                                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                                    setProgress { value ->
                                        scope.launch { scroll.scrollTo((value.coerceIn(0f, 1f) * scroll.maxValue).roundToInt()) }
                                        true
                                    }
                                }
                                .draggable(orientation = Orientation.Vertical, state = rememberDraggableState { delta ->
                                    scope.launch { scroll.scrollBy(delta * scroll.maxValue / travel) }
                                })) {
                                Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (fraction * travel).roundToInt()) }
                                    .width((12 * scale).dp.coerceAtLeast(6.dp)).height(with(density) { thumb.toDp() })
                                    .background(Color(0xFF999999), CircleShape))
                            }
                        }
                    }
                }
            }
            uiState.storiesError?.takeIf { uiState.stories.isNotEmpty() }?.let { error ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = Color(0xFFB3261E), fontFamily = ParkedFont, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("다시 불러오기", fontFamily = ParkedFont) }
                }
            }
            if (uiState.storiesSaved) Text("이야기를 저장했어요", color = Color(0xFF646464), fontFamily = ParkedFont,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            val buttonHeight = (144 * scale).dp.coerceAtLeast(if (compact) 48.dp else 80.dp)
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ParkedAction(if (uiState.savingStories) "저장 중…" else "이야기 저장", R.drawable.parked_folder,
                        onSave, uiState.canSelectStories && !uiState.savingStories && !uiState.storiesLoading && uiState.storiesError == null,
                        scale, Modifier.fillMaxWidth().heightIn(min = buttonHeight))
                    ParkedAction(if (uiState.skipUntilArrival) "기록 다시 시작하기" else "다음 목적지까지 기록 멈추기", R.drawable.parked_pause,
                        onToggleSkip, !uiState.savingStories, scale, Modifier.fillMaxWidth().heightIn(min = buttonHeight))
                    ParkedAction("여행 종료하기", null, onFinish, !uiState.savingStories,
                        scale, Modifier.fillMaxWidth().heightIn(min = buttonHeight))
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = (120 * scale).dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    ParkedAction(if (uiState.savingStories) "저장 중…" else "이야기 저장", R.drawable.parked_folder,
                        onSave, uiState.canSelectStories && !uiState.savingStories && !uiState.storiesLoading && uiState.storiesError == null,
                        scale, Modifier.weight(1f).heightIn(min = buttonHeight))
                    ParkedAction(if (uiState.skipUntilArrival) "기록 다시 시작하기" else "다음 목적지까지\n기록 멈추기", R.drawable.parked_pause,
                        onToggleSkip, !uiState.savingStories, scale, Modifier.weight(1f).heightIn(min = buttonHeight))
                    ParkedAction("여행 종료하기", null, onFinish, !uiState.savingStories,
                        scale, Modifier.weight(1f).heightIn(min = buttonHeight))
                }
            }
        }
    }
}

@Composable
private fun ParkedAction(label: String, icon: Int?, onClick: () -> Unit, enabled: Boolean,
    scale: Float, modifier: Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ParkedButton, contentColor = Color.Black),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        if (icon != null) {
            Image(painterResource(icon), null, Modifier.size((62 * scale).dp.coerceAtLeast(28.dp)))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontFamily = ParkedFont, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            fontSize = (32 * scale).coerceAtLeast(18f).sp)
    }
}
