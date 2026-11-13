package com.forever.dorolog

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val Pretendard = FontFamily(Font(R.font.pretendard_medium, FontWeight.Medium))
private val RoadColor = Color(0xFFF1E7F1)

@Composable
fun MainScreen(
    onStart: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
) {
    var destination by remember { mutableStateOf<String?>(null) }
    var dispatched by remember { mutableStateOf(false) }
    val white = remember { Animatable(0f) }
    val currentStart by rememberUpdatedState(onStart)
    val currentArchive by rememberUpdatedState(onArchive)
    val lifecycle = checkNotNull(LocalView.current.findViewTreeLifecycleOwner()).lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(destination, resumed) {
        if (destination == null || dispatched || !resumed) return@LaunchedEffect
        white.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        dispatched = true
        if (destination == "departure") currentStart() else currentArchive()
    }
    BackHandler(enabled = destination != null && !dispatched) {}

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(Color(0xFFFEFEFF))) {
        val referenceScale = minOf(maxWidth.value / 1700f, maxHeight.value / 1184f)
        val density = LocalDensity.current
        val startText = stringResource(R.string.start)
        val archiveText = stringResource(R.string.archive)
        val startStyle = TextStyle(
            fontFamily = Pretendard,
            fontWeight = FontWeight.Medium,
            fontSize = maxOf(48 * referenceScale, 24f).sp,
        )
        val startLabel = rememberTextMeasurer().measure(startText, startStyle)
        val startWidth = maxOf((298 * referenceScale).dp, 176.dp, with(density) { startLabel.size.width.toDp() } + 48.dp)
            .coerceAtMost(maxWidth - 32.dp)
        val startHeight = maxOf((97 * referenceScale).dp, 56.dp, with(density) { startLabel.size.height.toDp() } + 24.dp)
        val startTop = maxHeight - startHeight - maxOf((111 * referenceScale).dp, 24.dp)
        val archiveScale = maxOf(referenceScale, 0.5f)
        val archiveLabelScale = archiveScale * density.fontScale
        val archiveWidth = maxOf((194 * archiveScale).dp, (123 * archiveLabelScale).dp + 24.dp)
            .coerceAtMost(maxWidth - 32.dp)
        val archiveHeight = (116 * archiveScale + 45 * archiveLabelScale).dp + (15 * archiveScale).dp
        val margin = maxOf((54 * referenceScale).dp, 16.dp)
        val portrait = maxWidth < maxHeight
        val shortWindow = !portrait && maxHeight < 400.dp
        val brandScale = minOf(
            maxOf(referenceScale, 0.5f), maxWidth.value / 450f, (startTop - margin).value / 650f,
            if (shortWindow) maxHeight.value * 0.2f / 254f else Float.MAX_VALUE,
        )
        val brandTop = when {
            portrait -> margin + archiveHeight + 20.dp
            shortWindow -> margin
            else -> (158 * referenceScale).dp
        }
        val videoTop = minOf(
            maxOf(if (shortWindow) 0.dp else maxHeight * (425f / 1184f), brandTop + (254 * brandScale).dp + 12.dp),
            startTop - 48.dp,
        ).coerceAtLeast(0.dp)
        val videoHeight = maxWidth * (728f / 1668f)
        val sceneWidth = maxWidth

        Box(Modifier.offset(y = videoTop).size(maxWidth, maxHeight - videoTop).clipToBounds().testTag("entry-scene").background(RoadColor)) {
            EntryVideo(
                playing = animationsEnabled && resumed && destination == null,
                modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(sceneWidth, videoHeight).testTag("entry-video"),
            )
            // Blend the video's lower edge into the road when a tall window needs more ground.
            Box(
                Modifier.offset(y = (videoHeight - 24.dp).coerceAtLeast(0.dp))
                    .size(sceneWidth, 24.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, RoadColor))),
            )
        }
        Brand(
            scale = brandScale,
            modifier = Modifier.offset(x = maxWidth / 2 - (190 * brandScale).dp, y = brandTop),
        )
        Box(
            Modifier.offset(maxWidth - archiveWidth - margin, margin).size(archiveWidth, archiveHeight)
                .clip(RoundedCornerShape((44 * archiveScale).dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFF4F3FB), Color(0xFFEBE9F9))))
                .clickable(enabled = destination == null, role = Role.Button) { destination = "archive" }
                .semantics { text = AnnotatedString(archiveText) },
        ) {
            Artwork(
                R.drawable.main_archive_folder,
                Modifier.align(Alignment.TopCenter).offset(y = (10 * archiveScale).dp)
                    .size((114 * archiveScale).dp, (100 * archiveScale).dp)
                    .graphicsLayer { blendMode = BlendMode.Multiply },
            )
            Artwork(
                R.drawable.main_archive_label,
                Modifier.align(Alignment.TopCenter).offset(y = (117 * archiveScale + 10 * archiveLabelScale).dp)
                    .size((121 * archiveLabelScale).dp, (25 * archiveLabelScale).dp)
                    .graphicsLayer { blendMode = BlendMode.Multiply }.testTag("archive-label"),
            )
        }
        Box(
            Modifier.offset((maxWidth - startWidth) / 2, startTop).size(startWidth, startHeight)
                .clip(RoundedCornerShape(65.dp)).background(Color(0xFF7C79FF))
                .clickable(enabled = destination == null, role = Role.Button) { destination = "departure" },
            contentAlignment = Alignment.Center,
        ) {
            Text(startText, Modifier.fillMaxWidth(), color = Color.White, style = startStyle, textAlign = TextAlign.Center, maxLines = 1)
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = white.value }.background(Color.White))
    }
}

@Composable
private fun EntryVideo(playing: Boolean, modifier: Modifier) {
    var position by rememberSaveable { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(false) }
    Box(modifier) {
        Artwork(R.drawable.main_intro_poster, Modifier.fillMaxSize())
        if (!LocalInspectionMode.current && (playing || player != null)) {
            AndroidView(
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (rendered) 1f else 0f },
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(texture)
                                try {
                                    val video = createIntroPlayer(context)
                                    player = video
                                    video.setSurface(surface)
                                    video.setOnPreparedListener {
                                        video.seekTo(position)
                                        prepared = true
                                    }
                                    video.setOnInfoListener { _, what, _ ->
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered = true
                                        false
                                    }
                                    video.setOnErrorListener { _, what, extra ->
                                        Log.w("Dorolog", "Entry video unavailable: $what/$extra")
                                        prepared = false
                                        rendered = false
                                        true
                                    }
                                    video.prepareAsync()
                                } catch (exception: Exception) {
                                    Log.w("Dorolog", "Entry video could not be prepared", exception)
                                    player?.release()
                                    player = null
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                if (prepared) position = player?.currentPosition ?: position
                                player?.release()
                                player = null
                                prepared = false
                                rendered = false
                                surface?.release()
                                surface = null
                                return true
                            }
                        }
                    }
                },
                onRelease = { view ->
                    view.surfaceTexture?.let { view.surfaceTextureListener?.onSurfaceTextureDestroyed(it) }
                    view.surfaceTextureListener = null
                },
            )
        }
    }
    LaunchedEffect(player, prepared, playing) {
        val video = player ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        if (playing) video.start() else if (video.isPlaying) video.pause()
        while (playing && isActive) {
            position = video.currentPosition
            delay(200)
        }
    }
}

internal fun createIntroPlayer(context: Context): MediaPlayer = MediaPlayer().apply {
    try {
        context.resources.openRawResourceFd(R.raw.main_intro).use { asset ->
            setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
        }
        setVolume(0f, 0f)
        isLooping = true
        playbackParams = PlaybackParams().setSpeed(2f)
    } catch (exception: Exception) {
        release()
        throw exception
    }
}

@Composable
private fun Brand(scale: Float, modifier: Modifier) {
    val description = stringResource(R.string.brand)
    Box(modifier.size((380 * scale).dp, (254 * scale).dp).semantics { contentDescription = description }) {
        Artwork(R.drawable.main_logo, Modifier.brandSlot(742f, 200f, 245.184f, 140.674f, scale))
        Artwork(R.drawable.main_subtitle, Modifier.brandSlot(757f, 359f, 185.79f, 41.232f, scale))
        Artwork(R.drawable.logo_dash_left_yellow, Modifier.brandSlot(688f, 182f, 33.002f, 23.002f, scale))
        Artwork(R.drawable.logo_dash_left_purple, Modifier.brandSlot(718.16f, 158f, 22.036f, 33.522f, scale))
        Artwork(R.drawable.logo_dash_right_lower, Modifier.brandSlot(1000f, 342f, 23.001f, 19.001f, scale))
        Artwork(R.drawable.logo_dash_right_upper, Modifier.brandSlot(1007f, 321.52f, 25.81f, 10.179f, scale))
    }
}

private fun Modifier.brandSlot(x: Float, y: Float, width: Float, height: Float, scale: Float) =
    offset(((x - 680) * scale).dp, ((y - 158) * scale).dp).size((width * scale).dp, (height * scale).dp)

@Composable
private fun Artwork(resource: Int, modifier: Modifier) {
    Image(painterResource(resource), contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds)
}

@Preview(name = "Landscape", widthDp = 850, heightDp = 592)
@Preview(name = "Portrait", widthDp = 360, heightDp = 640)
@Preview(name = "Short landscape", widthDp = 640, heightDp = 320)
@Composable
private fun MainScreenPreview() {
    MainScreen(onStart = {}, onArchive = {}, animationsEnabled = false)
}
