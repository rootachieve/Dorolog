package com.forever.dorolog

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.PixelCopy
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.GestureType
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.CompetitionType
import com.kakao.vectormap.label.LabelLayerOptions
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private val MapBlue = Color(0xFF5E80F5)
private val thumbnailRenderer = Semaphore(1)
private val thumbnails = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}
private var kakaoInitialized = false

@Composable
internal fun ArchiveKakaoMap(
    regions: List<ArchiveRegion>,
    selectedRegionId: String?,
    onRegionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (BuildConfig.KAKAO_MAP_NATIVE_APP_KEY.isBlank() || LocalInspectionMode.current) {
        MapNotice("카카오맵 키 설정 후 지도가 표시됩니다", modifier)
        return
    }
    val density = LocalDensity.current.density
    val markerStyles = remember(regions, density) {
        regions.associate { region ->
            region.id to listOf(false, true).map { selected ->
                LabelStyle.from(regionMarker(region, selected, density))
                    .setApplyDpScale(false).setAnchorPoint(0.5f, 0.36f)
            }
        }
    }
    val currentClick by rememberUpdatedState(onRegionClick)
    var map by remember { mutableStateOf<KakaoMap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    Box(modifier.background(Color(0xFFEEF0F6))) {
        if (error == null) key(attempt) {
            NativeArchiveMap(
                position = LatLng.from(35.8, 127.8),
                zoom = 7,
                interactive = true,
                onReady = { _, readyMap ->
                    map = readyMap
                    readyMap.setOnLabelClickListener { _, _, label ->
                        (label.tag as? String)?.let(currentClick)
                        true
                    }
                },
                onError = { map = null; error = "지도를 불러오지 못했어요" },
                modifier = Modifier.fillMaxSize(),
            )
        }
        LaunchedEffect(map, regions, selectedRegionId) {
            val readyMap = map ?: return@LaunchedEffect
            val layer = readyMap.labelManager?.addLayer(
                LabelLayerOptions.from("archive-regions")
                    .setCompetitionType(CompetitionType.None).setClickable(true),
            ) ?: return@LaunchedEffect
            layer.removeAll()
            regions.forEach { region ->
                val style = markerStyles.getValue(region.id)[if (region.id == selectedRegionId) 1 else 0]
                layer.addLabel(
                    LabelOptions.from(region.id, LatLng.from(region.latitude, region.longitude))
                        .setStyles(style)
                        .setClickable(true).setTag(region.id),
                )
            }
        }
        LaunchedEffect(map, regions) {
            if (regions.isNotEmpty()) {
                map?.moveCamera(CameraUpdateFactory.fitMapPoints(
                    regions.map { LatLng.from(it.latitude, it.longitude) }.toTypedArray(),
                    (60 * density).roundToInt(), 10,
                ))
            }
        }
        if (error == null && map != null) {
            Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapZoomButton("+", "지도 확대") { map?.moveCamera(CameraUpdateFactory.zoomIn()) }
                MapZoomButton("−", "지도 축소") { map?.moveCamera(CameraUpdateFactory.zoomOut()) }
            }
        }
        if (error != null) {
            MapNotice(error!!, Modifier.fillMaxSize(), onRetry = { map = null; error = null; attempt++ })
        }
    }
}

@Composable
internal fun ArchiveDestinationMap(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    if (BuildConfig.KAKAO_MAP_NATIVE_APP_KEY.isBlank() || LocalInspectionMode.current) {
        MapNotice("카카오맵 키 설정 필요", modifier, compact = true)
        return
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val cacheKey = "$latitude,$longitude:${size.width}x${size.height}"
    var bitmap by remember(cacheKey) { mutableStateOf(thumbnails.get(cacheKey)) }
    var rendering by remember(cacheKey) { mutableStateOf(false) }
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    var attempt by remember(cacheKey) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    // The ready callback and its native view must belong to the same resumed lifecycle.
    var resumeGeneration by remember(lifecycle) { mutableIntStateOf(0) }
    val ready = remember(cacheKey, attempt, lifecycle, resumeGeneration) { CompletableDeferred<MapView>() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            val nowResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (nowResumed && !resumed) resumeGeneration++
            resumed = nowResumed
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(cacheKey, attempt, lifecycle, resumeGeneration, resumed) {
        if (!resumed || bitmap != null || size == IntSize.Zero) return@LaunchedEffect
        thumbnailRenderer.withPermit {
            try {
                rendering = true
                val view = withTimeout(20_000) { ready.await() }
                // shortcut: SDK에 타일 완료 콜백이 없어 1.5초 후 캡처한다. 느린 네트워크 검증 후 서버 썸네일로 교체한다.
                delay(1_500)
                val image = captureMap(view, size)
                thumbnails.put(cacheKey, image)
                bitmap = image
            } catch (_: TimeoutCancellationException) {
                error = "지도 연결 시간이 초과됐어요"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "지도를 불러오지 못했어요"
            } finally {
                rendering = false
            }
        }
    }
    Box(modifier.background(Color(0xFFE5E9F1)).onSizeChanged { size = it }) {
        val thumbnail = bitmap
        if (thumbnail != null) {
            Image(thumbnail.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("archive-destination-thumbnail"), contentScale = ContentScale.Crop)
        } else if (rendering) {
            key(cacheKey, attempt, lifecycle, resumeGeneration) {
                NativeArchiveMap(
                    position = LatLng.from(latitude, longitude),
                    zoom = 12,
                    interactive = false,
                    onReady = { view, map ->
                        val pin = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5E80F5.toInt() }
                        Canvas(pin).apply {
                            drawCircle(16f, 16f, 14f, paint)
                            paint.color = android.graphics.Color.WHITE
                            drawCircle(16f, 16f, 5f, paint)
                        }
                        map.labelManager?.layer?.addLabel(
                            LabelOptions.from(LatLng.from(latitude, longitude))
                                .setStyles(LabelStyle.from(pin).setAnchorPoint(0.5f, 0.5f)),
                        )
                        ready.complete(view)
                    },
                    onError = { ready.completeExceptionally(it) },
                    modifier = Modifier.fillMaxSize().testTag("archive-destination-renderer"),
                )
            }
        } else {
            MapNotice(error ?: "목적지 지도 불러오는 중", Modifier.fillMaxSize(), compact = true,
                onRetry = if (error == null) null else ({ error = null; attempt++ }))
        }
    }
}

@Composable
private fun NativeArchiveMap(
    position: LatLng,
    zoom: Int,
    interactive: Boolean,
    onReady: (MapView, KakaoMap) -> Unit,
    onError: (Exception) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentReady by rememberUpdatedState(onReady)
    val currentError by rememberUpdatedState(onError)
    val viewResult = remember {
        runCatching {
            if (!kakaoInitialized) {
                KakaoMapSdk.init(context.applicationContext, BuildConfig.KAKAO_MAP_NATIVE_APP_KEY)
                kakaoInitialized = true
            }
            MapView(context)
        }
    }
    val mapView = viewResult.getOrNull()
    if (mapView == null) {
        LaunchedEffect(Unit) { currentError(Exception("Map initialization failed", viewResult.exceptionOrNull())) }
        return
    }
    AndroidView(factory = {
        object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                interactive && super.dispatchTouchEvent(event)
        }.apply { addView(mapView, FrameLayout.LayoutParams(-1, -1)) }
    }, modifier = modifier)
    DisposableEffect(mapView, lifecycle) {
        var active = true
        try {
            mapView.start(object : MapLifeCycleCallback() {
                override fun onMapDestroy() = Unit
                override fun onMapError(error: Exception) { if (active) currentError(error) }
            }, object : KakaoMapReadyCallback() {
                override fun getPosition() = position
                override fun getZoomLevel() = zoom
                override fun onMapReady(kakaoMap: KakaoMap) {
                    if (!active) return
                    kakaoMap.setCameraAnimateEnable(false)
                    if (!interactive) {
                        GestureType.values().forEach { kakaoMap.setGestureEnable(it, false) }
                        kakaoMap.setPoiClickable(false)
                    }
                    currentReady(mapView, kakaoMap)
                }
            })
            mapView.setFinishManually(true)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.resume() else mapView.pause()
        } catch (exception: Exception) {
            currentError(exception)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.resume()
                Lifecycle.Event.ON_PAUSE -> mapView.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            active = false
            lifecycle.removeObserver(observer)
            mapView.pause()
            mapView.finish()
        }
    }
}

private suspend fun captureMap(view: MapView, size: IntSize): Bitmap = suspendCancellableCoroutine { continuation ->
    val scale = minOf(1f, 640f / size.width, 400f / size.height)
    val bitmap = Bitmap.createBitmap((size.width * scale).roundToInt().coerceAtLeast(1),
        (size.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(requireNotNull(view.surfaceView), bitmap, { result ->
            if (continuation.isActive) {
                if (result == PixelCopy.SUCCESS) continuation.resume(bitmap)
                else {
                    bitmap.recycle()
                    continuation.resumeWithException(IllegalStateException("Map capture failed: $result"))
                }
            } else bitmap.recycle()
        }, Handler(Looper.getMainLooper()))
    } catch (exception: IllegalArgumentException) {
        bitmap.recycle()
        if (continuation.isActive) continuation.resumeWithException(exception)
    }
}

private fun regionMarker(region: ArchiveRegion, selected: Boolean, density: Float): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
    }
    val width = maxOf(64 * density, paint.measureText(region.name) + 12 * density).roundToInt()
    val bitmap = Bitmap.createBitmap(width, (88 * density).roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centerX = width / 2f
    val centerY = 31 * density
    paint.color = if (selected) 0xFF5E80F5.toInt() else 0xFFECF1FF.toInt()
    canvas.drawCircle(centerX, centerY, 28 * density, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2 * density
    paint.color = 0xFF5E80F5.toInt()
    canvas.drawCircle(centerX, centerY, 28 * density, paint)
    paint.style = Paint.Style.FILL
    paint.textSize = 23 * density
    paint.color = if (selected) android.graphics.Color.WHITE else 0xFF4F6FE4.toInt()
    canvas.drawText(region.tripCount.toString(), centerX, centerY - (paint.ascent() + paint.descent()) / 2, paint)
    paint.color = 0xFF333333.toInt()
    paint.textSize = 16 * density
    canvas.drawText(region.name, centerX, 80 * density, paint)
    return bitmap
}

@Composable
private fun MapZoomButton(label: String, description: String, onClick: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp) {
        TextButton(onClick = onClick, modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp)
            .semantics { contentDescription = description }) {
            Text(label, fontSize = 28.sp, color = MapBlue)
        }
    }
}

@Composable
private fun MapNotice(message: String, modifier: Modifier, compact: Boolean = false, onRetry: (() -> Unit)? = null) {
    Box(modifier.background(Color(0xFFE5E9F1)).padding(12.dp),
        contentAlignment = if (compact) Alignment.TopCenter else Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color(0xFF555B6D), fontSize = if (compact) 13.sp else 18.sp, textAlign = TextAlign.Center)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("지도 다시 불러오기", color = MapBlue) }
        }
    }
}
