package com.forever.dorolog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ArchiveFont = FontFamily(
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)
private val ArchivePurple = Color(0xFF7C79FF)
private val ArchiveBackground = Color(0xFFF7F8FD)
private val ArchiveDate = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)

@Composable
internal fun ArchiveRoute(
    onBack: () -> Unit,
    onTripClick: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    browsingAllowed: Boolean = true,
    viewModel: ArchiveViewModel = viewModel(factory = viewModelFactory {
        initializer { ArchiveViewModel() }
    }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ArchiveScreen(state, onBack, onTripClick, viewModel::selectTab, viewModel::selectSort,
        viewModel::selectRegion, viewModel::retry, modifier, browsingAllowed)
}

@Composable
internal fun ArchiveScreen(
    uiState: ArchiveViewModel.UiState,
    onBack: () -> Unit,
    onTripClick: (String, String?) -> Unit,
    onTabSelected: (ArchiveTab) -> Unit,
    onSortSelected: (ArchiveSort) -> Unit,
    onRegionSelected: (String?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    browsingAllowed: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(ArchiveBackground).testTag("archive-screen")) {
        val scale = minOf(maxWidth.value / 1700f, maxHeight.value / 1184f).coerceIn(0.55f, 1.2f)
        val margin = if (maxWidth < 580.dp) 16.dp else (63 * scale).dp.coerceAtMost(32.dp)
        val wide = maxWidth >= 720.dp && maxHeight >= 420.dp
        val twoColumnsFit = maxWidth >= 580.dp
        val textSize = (32 * scale).coerceAtLeast(18f).sp
        val gridState = rememberLazyGridState()
        val regionListState = rememberLazyListState()
        var scrollRegion by rememberSaveable { mutableStateOf(uiState.selectedRegionId) }
        var scrollSort by rememberSaveable { mutableStateOf(uiState.sort.name) }
        LaunchedEffect(uiState.selectedRegionId, uiState.sort) {
            if (scrollRegion != uiState.selectedRegionId || scrollSort != uiState.sort.name) {
                regionListState.scrollToItem(0)
                gridState.scrollToItem(0)
                scrollRegion = uiState.selectedRegionId
                scrollSort = uiState.sort.name
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = margin, vertical = (32 * scale).dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Image(painterResource(R.drawable.archive_dot), null, Modifier.padding(top = 3.dp).size((16 * scale).dp))
                    Text(stringResource(R.string.story_map), fontSize = (48 * scale).coerceAtLeast(24f).sp,
                        fontFamily = ArchiveFont, fontWeight = FontWeight.SemiBold, color = Color.Black)
                }
                ArchivePill(onClick = onBack, color = ArchivePurple) {
                    Text(stringResource(R.string.archive_home), fontFamily = ArchiveFont, fontSize = textSize, color = Color.White)
                    Image(painterResource(R.drawable.archive_chevron_right), null, Modifier.size((30 * scale).dp))
                }
            }
            Spacer(Modifier.height((32 * scale).dp))
            if (!browsingAllowed) {
                ArchiveMessage(stringResource(R.string.archive_parked_only), Modifier.weight(1f))
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy((16 * scale).dp)) {
                ArchiveTab.entries.forEach { tab ->
                    val selected = uiState.tab == tab
                    ArchivePill(
                        onClick = { onTabSelected(tab) }, color = if (selected) ArchivePurple else Color.White,
                        modifier = Modifier.semantics { this.selected = selected },
                    ) {
                        Text(stringResource(if (tab == ArchiveTab.Trips) R.string.archive_trips else R.string.archive_map),
                            fontFamily = ArchiveFont, fontSize = textSize, color = if (selected) Color.White else Color(0xFF222222))
                    }
                }
            }
            if (uiState.content.isSample) {
                Text(stringResource(R.string.archive_sample), Modifier.padding(top = 8.dp),
                    fontSize = 14.sp, fontFamily = ArchiveFont, color = Color(0xFF646464))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (uiState.tab == ArchiveTab.Map && uiState.selectedRegionId != null) {
                        uiState.content.regions.firstOrNull { it.id == uiState.selectedRegionId }?.name.orEmpty()
                    } else "",
                    Modifier.weight(1f), fontFamily = ArchiveFont, fontSize = textSize, color = Color.Black,
                )
                if (uiState.tab == ArchiveTab.Map && uiState.selectedRegionId != null) {
                    TextButton(onClick = { onRegionSelected(null) }) {
                        Text(stringResource(R.string.archive_all), fontFamily = ArchiveFont, color = Color(0xFF5A46FA), fontSize = 16.sp)
                    }
                }
                ArchiveSortMenu(uiState.sort, onSortSelected, scale)
            }
            when {
                uiState.isLoading -> ArchiveMessage(stringResource(R.string.archive_loading), Modifier.weight(1f))
                uiState.error != null -> Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(uiState.error, fontFamily = ArchiveFont, fontSize = 20.sp, color = Color(0xFF646464), textAlign = TextAlign.Center)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = Color(0xFF5A46FA), fontSize = 20.sp) }
                }
                (if (uiState.tab == ArchiveTab.Trips) uiState.content.trips.isEmpty() else uiState.content.stories.isEmpty()) -> ArchiveMessage(stringResource(R.string.archive_empty), Modifier.weight(1f))
                uiState.tab == ArchiveTab.Trips -> {
                    val fontScale = LocalDensity.current.fontScale
                    val columns = if (wide && fontScale <= 1.3f) 3 else if (twoColumnsFit) 2 else 1
                    LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState,
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("archive-trip-grid"),
                        horizontalArrangement = Arrangement.spacedBy((24 * scale).dp),
                        verticalArrangement = Arrangement.spacedBy((36 * scale).dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(uiState.visibleTrips, key = { it.id }) { trip -> ArchiveTripCard(trip, onTripClick, scale) }
                    }
                }
                else -> if (wide) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy((48 * scale).dp)) {
                        ArchiveMapPanel(uiState, onRegionSelected, Modifier.weight(0.47f).fillMaxHeight())
                        ArchiveRegionStories(uiState, onTripClick, scale, regionListState, Modifier.weight(0.53f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ArchiveMapPanel(uiState, onRegionSelected, Modifier.fillMaxWidth().weight(0.52f))
                        ArchiveRegionStories(uiState, onTripClick, scale, regionListState, Modifier.fillMaxWidth().weight(0.48f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivePill(onClick: () -> Unit, color: Color, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.clip(RoundedCornerShape(66.dp)).background(color).clickable(role = Role.Button, onClick = onClick)
        .sizeIn(minHeight = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun ArchiveSortMenu(sort: ArchiveSort, onSelected: (ArchiveSort) -> Unit, scale: Float) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ArchivePill(onClick = { expanded = true }, color = Color(0xFF646464)) {
            Text(sort.label, fontFamily = ArchiveFont, fontSize = (28 * scale).coerceAtLeast(16f).sp, color = Color.White)
                    Image(painterResource(R.drawable.archive_chevron_sort), null, Modifier.size((23 * scale).dp, (24 * scale).dp).rotate(90f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ArchiveSort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label, fontFamily = ArchiveFont, fontSize = 20.sp) },
                    onClick = { expanded = false; onSelected(option) }, modifier = Modifier.semantics { selected = option == sort })
            }
        }
    }
}

@Composable
private fun ArchiveTripCard(trip: ArchiveTrip, onClick: (String, String?) -> Unit, scale: Float) {
    Box(Modifier.fillMaxWidth().heightIn(min = (294 * scale).dp.coerceAtLeast(180.dp))
        .aspectRatio(508f / 294f).clip(RoundedCornerShape((24 * scale).dp)).background(Color(0xFFE9EBF3))
        .clickable(role = Role.Button) { onClick(trip.id, trip.destinationName) }.testTag("archive-trip-${trip.id}")) {
        ArchiveDestinationMap(trip.destinationLatitude, trip.destinationLongitude, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color(0xE0131417))))
        Column(Modifier.align(Alignment.BottomStart).padding((27 * scale).dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trip.destinationName?.let { "${trip.departureName}\n→ $it" } ?: trip.departureName, fontSize = (36 * scale).coerceAtLeast(20f).sp,
                lineHeight = (44 * scale).coerceAtLeast(26f).sp, color = Color.White, fontFamily = ArchiveFont,
                fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(trip.date.format(ArchiveDate), fontSize = (20 * scale).coerceAtLeast(14f).sp,
                color = Color.White.copy(alpha = 0.85f), fontFamily = ArchiveFont)
        }
    }
}

@Composable
private fun ArchiveMapPanel(state: ArchiveViewModel.UiState, onRegionSelected: (String?) -> Unit, modifier: Modifier) {
    var regionMenuOpen by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.archive_story_count, state.content.storyCount), fontFamily = ArchiveFont, color = Color(0xFF5A46FA), fontSize = 16.sp)
                Text(stringResource(R.string.archive_trip_count, state.content.tripCount), fontFamily = ArchiveFont, color = Color.Black, fontSize = 16.sp)
            }
            Box {
                TextButton(onClick = { regionMenuOpen = true }) {
                    Text(stringResource(R.string.archive_choose_region), fontFamily = ArchiveFont, color = Color(0xFF5A46FA), fontSize = 16.sp)
                }
                DropdownMenu(expanded = regionMenuOpen, onDismissRequest = { regionMenuOpen = false }) {
                    state.content.regions.forEach { region ->
                        DropdownMenuItem(
                            text = { Text("${region.name} ${region.storyCount}", fontFamily = ArchiveFont, fontSize = 18.sp) },
                            onClick = { regionMenuOpen = false; onRegionSelected(region.id) },
                            modifier = Modifier.semantics { selected = region.id == state.selectedRegionId },
                        )
                    }
                }
            }
        }
        ArchiveKakaoMap(state.content.regions, state.selectedRegionId, onRegionSelected,
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)))
    }
}

@Composable
private fun ArchiveRegionStories(state: ArchiveViewModel.UiState, onClick: (String, String?) -> Unit, scale: Float, listState: LazyListState, modifier: Modifier) {
    LazyColumn(modifier.testTag("archive-region-trips"), state = listState,
        verticalArrangement = Arrangement.spacedBy((20 * scale).dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(state.visibleStories, key = { _, story -> story.tripId to story.id }) { index, story ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape((36 * scale).dp)).background(Color.White)
                .clickable(role = Role.Button) {
                    onClick(story.tripId, state.content.trips.firstOrNull { it.id == story.tripId }?.destinationName)
                }.testTag("archive-map-story-${story.id}")
                .heightIn(min = (167 * scale).dp).padding((32 * scale).dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((23 * scale).dp)) {
                Box(Modifier.size((64 * scale).dp.coerceAtLeast(40.dp)).background(Color.Black, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", fontFamily = ArchiveFont, fontSize = (32 * scale).coerceAtLeast(18f).sp, color = Color.White)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(story.title, fontSize = (32 * scale).coerceAtLeast(20f).sp, fontFamily = ArchiveFont,
                        color = Color.Black, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${story.region} · ${story.date?.format(ArchiveDate) ?: "날짜 정보 없음"}", fontSize = (24 * scale).coerceAtLeast(14f).sp,
                        fontFamily = ArchiveFont, color = Color(0xFF646464), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ArchiveMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(message, fontFamily = ArchiveFont, fontSize = 20.sp, color = Color(0xFF646464), textAlign = TextAlign.Center)
    }
}

@Preview(widthDp = 1100, heightDp = 760)
@Composable
private fun ArchiveTripsPreview() {
    ArchiveScreen(ArchiveViewModel.UiState(content = sampleArchiveContent, isLoading = false), {}, { _, _ -> }, {}, {}, {}, {})
}

@Preview(widthDp = 1100, heightDp = 760)
@Composable
private fun ArchiveMapPreview() {
    ArchiveScreen(ArchiveViewModel.UiState(tab = ArchiveTab.Map, content = sampleArchiveContent, isLoading = false), {}, { _, _ -> }, {}, {}, {}, {})
}
