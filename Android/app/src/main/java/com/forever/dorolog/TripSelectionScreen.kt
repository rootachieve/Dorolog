package com.forever.dorolog

import android.car.Car
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SelectionFont = FontFamily(
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)
private val SelectionDate = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)

@Composable
internal fun TripSelectionRoute(
    onBack: () -> Unit,
    onTripStarted: (String, Destination?) -> Unit,
    modifier: Modifier = Modifier,
    car: Car? = null,
    browsingAllowed: Boolean = true,
    repository: TripSelectionRepository? = null,
    viewModel: TripSelectionViewModel? = null,
) {
    val owner = remember {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel ?: androidx.lifecycle.viewmodel.compose.viewModel<TripSelectionViewModel>(
        viewModelStoreOwner = owner,
        factory = viewModelFactory { initializer {
            TripSelectionViewModel(repository ?: if (BuildConfig.TRIP_API_BASE_URL.isBlank())
                TripSelectionRepository() else TripSelectionRepository.remote(BuildConfig.TRIP_API_BASE_URL))
        } },
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    SideEffect { vm.setBrowsingAllowed(browsingAllowed) }
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(uiState.toastMessage, lifecycleState) {
        if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            uiState.toastMessage?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                vm.consumeToast()
            }
        }
    }
    val source = remember(vm, context.applicationContext) {
        DepartureVehicleSource(context.applicationContext, vm::onNavigationChanged,
            onSpeed = {}, onLocation = vm::onLocationChanged, observeVehicle = false,
            onDestinationSet = { vm.onNavigationChanged(null, false) })
    }
    LifecycleResumeEffect(car, source) {
        source.start(car)
        onPauseOrDispose { source.stop(); vm.clearVehicleContext() }
    }
    val onStarted by rememberUpdatedState(onTripStarted)
    LaunchedEffect(uiState.startedTripId) {
        uiState.startedTripId?.let { onStarted(it, uiState.startedDestination) }
    }
    TripSelectionScreen(uiState, onBack,
        onNewTrip = { if (browsingAllowed) vm.startNewTrip() },
        onResumeTrip = { if (browsingAllowed) vm.resumeTrip(it) },
        onRetry = vm::retry,
        onDismissDialog = vm::dismissDestinationDialog,
        onStartWithoutDestination = { if (browsingAllowed) vm.confirmWithoutDestination() },
        modifier = modifier, browsingAllowed = browsingAllowed)
}

@Composable
internal fun TripSelectionScreen(
    uiState: TripSelectionViewModel.UiState,
    onBack: () -> Unit,
    onNewTrip: () -> Unit,
    onResumeTrip: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissDialog: () -> Unit,
    onStartWithoutDestination: () -> Unit,
    modifier: Modifier = Modifier,
    browsingAllowed: Boolean = true,
) {
    val back = {
        if (!uiState.starting) {
            if (browsingAllowed && uiState.showDestinationDialog) onDismissDialog() else onBack()
        }
    }
    BackHandler(onBack = back)
    BoxWithConstraints(modifier.fillMaxSize().background(ForeverColors.selectionBackground)
        .testTag("trip-selection-screen")) {
        val fontScale = LocalDensity.current.fontScale
        val wide = maxWidth >= 900.dp && maxHeight >= 420.dp && fontScale <= 1.3f
        val scale = (maxWidth.value / 1700f).coerceIn(0.55f, 1.25f)
        val panelMaxHeight = (maxHeight - 160.dp).coerceAtLeast(180.dp)
        val margin = if (maxWidth < 600.dp) 16.dp else 32.dp
        val columns = if (wide) 3 else if (maxWidth >= 600.dp && fontScale <= 1.3f) 2 else 1
        val heading: @Composable () -> Unit = {
            Text("최근 주행에 이어서 기록할까요?", fontFamily = SelectionFont,
                fontWeight = FontWeight.SemiBold, fontSize = if (wide) (44 * scale).coerceAtLeast(24f).sp else 24.sp,
                color = ForeverColors.selectionTitle)
            if (browsingAllowed) Text("새로 기록하려면 ‘새로 시작’을 눌러 주세요.",
                fontFamily = SelectionFont, fontSize = if (wide) (22 * scale).coerceAtLeast(16f).sp else 16.sp,
                color = ForeverColors.selectionBody)
        }
        val startStatus: @Composable () -> Unit = {
            if (uiState.starting && !uiState.showDestinationDialog) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = ForeverColors.selectionAccent)
                    Text("여행을 시작하고 있어요", fontFamily = SelectionFont,
                        color = ForeverColors.selectionBody, fontSize = 18.sp)
                }
            }
            if (uiState.startError != null && !uiState.canRetryStart && !uiState.showDestinationDialog) {
                TextButton(onClick = onRetry, enabled = !uiState.starting,
                    modifier = Modifier.heightIn(min = 64.dp)) {
                    Text("다시 불러오기", fontFamily = SelectionFont, fontSize = 18.sp,
                        color = ForeverColors.selectionAccent)
                }
            }
        }
        val contentModifier = if (wide) Modifier.fillMaxWidth().align(Alignment.Center) else Modifier.fillMaxSize()
        Column(contentModifier.padding(horizontal = margin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { heading() }
                } else Spacer(Modifier.weight(1f))
                TextButton(onClick = back, enabled = !uiState.starting,
                    modifier = Modifier.heightIn(min = 64.dp)) {
                    Text("뒤로가기", fontFamily = SelectionFont, fontSize = 18.sp,
                        color = ForeverColors.selectionAccent)
                }
            }
            if (!browsingAllowed) {
                Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Text("주차 중에 여행을 선택할 수 있어요", fontFamily = SelectionFont,
                        fontSize = 20.sp, color = ForeverColors.selectionBody, textAlign = TextAlign.Center)
                }
                return@Column
            }
            if (wide) {
                startStatus()
                BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight)) {
                    val newCardWidth = maxWidth * 0.225f
                    val cardHeight = ((maxWidth - newCardWidth - 52.dp) / 3 / 1.33f).coerceAtLeast(180.dp)
                    val panelHeight = (cardHeight * 2 + 16.dp).coerceAtMost(maxHeight)
                    Row(Modifier.fillMaxWidth().height(panelHeight), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        NewTripCard(onNewTrip, !uiState.starting && uiState.canStartNewTrip, false, Modifier.width(newCardWidth).fillMaxHeight(), scale)
                        SelectionTrips(uiState, onResumeTrip, onRetry, columns, Modifier.weight(1f).fillMaxHeight(), scale)
                    }
                }
            } else {
                SelectionTrips(uiState, onResumeTrip, onRetry, columns, Modifier.fillMaxWidth().weight(1f), scale,
                    header = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            heading()
                            NewTripCard(onNewTrip, !uiState.starting && uiState.canStartNewTrip, true, Modifier.fillMaxWidth().heightIn(min = 96.dp))
                            startStatus()
                        }
                    })
            }
        }
    }

    if (browsingAllowed && uiState.showDestinationDialog) {
        DestinationMissingDialog(uiState.starting, onDismissDialog, onStartWithoutDestination)
    }
}

@Composable
private fun NewTripCard(onClick: () -> Unit, enabled: Boolean, compact: Boolean, modifier: Modifier, scale: Float = 1f) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.clip(shape).background(ForeverColors.selectionAccent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(20.dp),
        contentAlignment = Alignment.Center) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                NewTripPlus(56)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("새로 시작", fontFamily = SelectionFont, fontWeight = FontWeight.SemiBold,
                        fontSize = 24.sp, color = ForeverColors.selectionOnAccent)
                    Text("새 주행으로 기록해요", fontFamily = SelectionFont, fontSize = 16.sp,
                        color = ForeverColors.selectionOnAccent)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NewTripPlus((132 * scale).toInt().coerceAtLeast(72))
                Spacer(Modifier.height(4.dp))
                Text("새로 시작", fontFamily = SelectionFont, fontWeight = FontWeight.SemiBold,
                    fontSize = (36 * scale).coerceAtLeast(24f).sp, color = ForeverColors.selectionOnAccent)
                Text("새 주행으로 기록해요", fontFamily = SelectionFont, fontSize = (20 * scale).coerceAtLeast(16f).sp,
                    color = ForeverColors.selectionOnAccent, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun NewTripPlus(size: Int) {
    Box(Modifier.size(size.dp).background(ForeverColors.selectionAddCircle, CircleShape),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size((size * 0.36f).dp, 4.dp).background(ForeverColors.selectionOnAccent, CircleShape))
        Box(Modifier.size(4.dp, (size * 0.36f).dp).background(ForeverColors.selectionOnAccent, CircleShape))
    }
}

@Composable
private fun SelectionTrips(
    uiState: TripSelectionViewModel.UiState,
    onResumeTrip: (String) -> Unit,
    onRetry: () -> Unit,
    columns: Int,
    modifier: Modifier,
    scale: Float,
    header: (@Composable () -> Unit)? = null,
) {
    LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = modifier.testTag("trip-selection-list"),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 16.dp)) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        when {
            uiState.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    CircularProgressIndicator(color = ForeverColors.selectionAccent)
                    Text("여행 목록을 가져오고 있어요", fontFamily = SelectionFont,
                        color = ForeverColors.selectionBody, fontSize = 18.sp)
                }
            }
            uiState.listError != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onRetry, enabled = !uiState.starting, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text("다시 불러오기", fontFamily = SelectionFont, color = ForeverColors.selectionAccent, fontSize = 18.sp)
                    }
                }
            }
            uiState.trips.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("이어서 기록할 여행이 없어요\n새 주행을 시작해 보세요", fontFamily = SelectionFont,
                        color = ForeverColors.selectionBody, fontSize = 20.sp, textAlign = TextAlign.Center)
                }
            }
            else -> itemsIndexed(uiState.trips, key = { _, trip -> trip.id }) { index, trip ->
                SelectionTripCard(trip, index == 0, !uiState.starting, scale) { onResumeTrip(trip.id) }
            }
        }
    }
}

@Composable
private fun SelectionTripCard(trip: OngoingTrip, latest: Boolean, enabled: Boolean, scale: Float, onClick: () -> Unit) {
    val place = trip.lastDestination ?: trip.origin
    val originName = trip.origin.name.ifBlank { trip.origin.address.ifBlank { "출발지" } }
    val destinationName = trip.lastDestination?.let { it.name.ifBlank { it.address.ifBlank { "목적지" } } } ?: "목적지 없음"
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).aspectRatio(1.33f)
        .clip(RoundedCornerShape(24.dp)).background(ForeverColors.selectionMapPlaceholder)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { selected = latest }.testTag("resume-${trip.id}")) {
        val latitude = place.latitude
        val longitude = place.longitude
        if (latitude != null && longitude != null) ArchiveDestinationMap(latitude, longitude, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0.3f to ForeverColors.selectionTransparent, 1f to ForeverColors.selectionMapShade)))
        Surface(Modifier.align(Alignment.TopEnd).padding(12.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(32.dp),
            color = if (latest) ForeverColors.selectionAccent else ForeverColors.selectionSurface) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("이어가기 →", fontFamily = SelectionFont, fontWeight = FontWeight.SemiBold,
                    fontSize = (22 * scale).coerceAtLeast(16f).sp,
                    color = if (latest) ForeverColors.selectionOnAccent else ForeverColors.selectionAccent)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$originName → $destinationName",
                fontFamily = SelectionFont, fontWeight = FontWeight.SemiBold, fontSize = (32 * scale).coerceAtLeast(20f).sp,
                color = ForeverColors.selectionOnAccent, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${trip.lastDrivenAt.atZone(ZoneId.systemDefault()).format(SelectionDate)} · 이야기 ${trip.storyCount}개",
                fontFamily = SelectionFont, fontSize = (20 * scale).coerceAtLeast(14f).sp, color = ForeverColors.selectionOnAccent,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DestinationMissingDialog(starting: Boolean, onDismiss: () -> Unit, onStart: () -> Unit) {
    Dialog(onDismissRequest = { if (!starting) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !starting, dismissOnClickOutside = !starting,
            usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0.45f)
        }
        Surface(Modifier.padding(24.dp).widthIn(max = 900.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp), color = ForeverColors.selectionSurface) {
            BoxWithConstraints {
                val spacious = maxWidth >= 800.dp && maxHeight >= 480.dp
                val buttonHeight = if (spacious) 100.dp else 64.dp
                val buttonText = if (spacious) 24.sp else 18.sp
                Column(Modifier.verticalScroll(rememberScrollState()).padding(
                horizontal = if (spacious) 36.dp else 32.dp, vertical = if (spacious) 70.dp else 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (spacious) 24.dp else 16.dp)) {
                Text("내비게이션 목적지가 아직 없어요", fontFamily = SelectionFont,
                    fontWeight = FontWeight.SemiBold, fontSize = if (spacious) 36.sp else 28.sp,
                    color = ForeverColors.selectionTitle, textAlign = TextAlign.Center)
                Text("내비게이션에서 목적지를 설정하면 자동으로 기록을 시작해요.", fontFamily = SelectionFont,
                    fontSize = if (spacious) 22.sp else 18.sp, color = ForeverColors.selectionBody, textAlign = TextAlign.Center)
                if (starting) CircularProgressIndicator(Modifier.size(28.dp), color = ForeverColors.selectionAccent)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onStart, enabled = !starting, modifier = Modifier.weight(1f).heightIn(min = buttonHeight),
                        shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(
                            containerColor = ForeverColors.selectionSecondary, contentColor = ForeverColors.selectionAccent)) {
                        Text("목적지 없이 시작", fontFamily = SelectionFont, fontSize = buttonText, fontWeight = FontWeight.SemiBold)
                    }
                    Button(onClick = onDismiss, enabled = !starting, modifier = Modifier.weight(1f).heightIn(min = buttonHeight),
                        shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(
                            containerColor = ForeverColors.selectionAccent, contentColor = ForeverColors.selectionOnAccent)) {
                        Text("닫기", fontFamily = SelectionFont, fontSize = buttonText, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            }
        }
    }
}
