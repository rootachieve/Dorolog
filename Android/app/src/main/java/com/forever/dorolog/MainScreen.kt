package com.forever.dorolog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

private val Ink = Color(0xFF131417)
private val Pretendard = FontFamily(
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
)

@Composable
fun MainScreen(
    onStart: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
) {
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var dispatched by rememberSaveable { mutableStateOf(false) }
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

    BoxWithConstraints(
        modifier.fillMaxSize().clipToBounds().background(
            Brush.verticalGradient(0f to Color(0xFFFAF9FD), 0.428f to Color(0xFFFDFCFF)),
        ),
    ) {
        val referenceScale = maxWidth.value / 1700f
        val startText = stringResource(R.string.start)
        val archiveText = stringResource(R.string.archive)
        val startFontSize = maxOf(48 * referenceScale, 24f)
        val archiveFontSize = maxOf(32 * referenceScale, 18f)
        val startStyle = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Medium, fontSize = startFontSize.sp)
        val archiveStyle = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = archiveFontSize.sp)
        val measurer = rememberTextMeasurer()
        val startLabel = measurer.measure(startText, startStyle)
        val archiveLabel = measurer.measure(archiveText, archiveStyle)
        val density = LocalDensity.current
        val startWidth = maxOf((298 * referenceScale).dp, 176.dp, with(density) { startLabel.size.width.toDp() } + 48.dp).coerceAtMost(maxWidth - 32.dp)
        val archiveWidth = maxOf((281 * referenceScale).dp, 176.dp, with(density) { archiveLabel.size.width.toDp() } + 48.dp).coerceAtMost(maxWidth - 32.dp)
        val startHeight = maxOf((97 * referenceScale).dp, 56.dp, with(density) { startLabel.size.height.toDp() } + 24.dp)
        val archiveHeight = maxOf((144 * referenceScale).dp, 64.dp, with(density) { archiveLabel.size.height.toDp() } + 32.dp)
        val rightMargin = (90 * referenceScale).dp
        val narrow = maxWidth < maxHeight || maxWidth / 2 - startWidth / 2 < archiveWidth + rightMargin + 16.dp
        val archiveTop = maxHeight - archiveHeight - if (narrow) 24.dp else (maxHeight * (88f / 1184f)).coerceAtLeast(24.dp)
        val startTop = if (narrow) archiveTop - 12.dp - startHeight
            else maxHeight - startHeight - (maxHeight * (119f / 1184f)).coerceAtLeast(24.dp)
        val sceneHeight = minOf(maxHeight, (startTop * (1184f / 968f)).coerceAtLeast(0.dp))
        val artScale = minOf(referenceScale, sceneHeight.value / 900f)
        val artInset = (maxWidth - (1700 * artScale).dp) / 2
        val waveTop = sceneHeight * (638f / 1184f)
        val brandScale = maxOf(artScale, 0.5f).coerceAtMost(sceneHeight.value * 0.34f / 260f)
        Brand(
            scale = brandScale,
            modifier = Modifier.offset(
                x = maxWidth / 2 - (200 * brandScale).dp,
                y = sceneHeight * (327f / 1184f) - (132 * brandScale).dp,
            ),
        )
        Characters.forEach { character ->
            FloatingCharacter(
                character,
                artScale,
                animationsEnabled && resumed && destination == null,
                Modifier.offset(
                    artInset + (character.x * artScale).dp,
                    waveTop + ((character.y - 638) * artScale).dp,
                ),
            )
        }
        val waveHeight = (583 * referenceScale).dp
        val fillTop = waveTop + waveHeight - 1.dp
        if (fillTop < maxHeight) {
            Box(Modifier.offset(y = fillTop).size(maxWidth, maxHeight - fillTop).background(Color(0xFFEDE2EE)))
        }
        Artwork(
            R.drawable.main_wave,
            Modifier.offset((-70 * referenceScale).dp, waveTop)
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .size((1836 * referenceScale).dp, waveHeight),
        )

        MainAction(
            text = startText,
            primary = true,
            enabled = destination == null,
            textStyle = startStyle,
            modifier = Modifier.offset((maxWidth - startWidth) / 2, startTop).size(startWidth, startHeight),
            onClick = { if (destination == null) destination = "departure" },
        )
        MainAction(
            text = archiveText,
            primary = false,
            enabled = destination == null,
            textStyle = archiveStyle,
            modifier = Modifier.offset(
                if (narrow) (maxWidth - archiveWidth) / 2 else maxWidth - archiveWidth - rightMargin,
                archiveTop,
            ).size(archiveWidth, archiveHeight),
            onClick = { if (destination == null) destination = "archive" },
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = white.value }.background(Color.White))
    }
}

@Composable
private fun MainAction(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    textStyle: TextStyle,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(if (primary) 65.dp else 24.dp))
            .background(if (primary) Color(0xFF7365D9) else Color(0xFFF7F2FE))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            color = if (primary) Color.White else Color.Black,
            style = textStyle,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun Brand(scale: Float, modifier: Modifier) {
    val description = stringResource(R.string.brand)
    Box(modifier.size((400 * scale).dp, (260 * scale).dp).semantics { contentDescription = description }) {
        Artwork(R.drawable.main_logo, Modifier.brandSlot(742f, 242f, 245.184f, 140.674f, scale))
        Artwork(R.drawable.main_subtitle, Modifier.brandSlot(758.056f, 408.744f, 185.79f, 41.232f, scale))
        Artwork(R.drawable.logo_dash_left_yellow, Modifier.brandSlot(655.999f, 219.999f, 33.002f, 23.002f, scale))
        Artwork(R.drawable.logo_dash_left_purple, Modifier.brandSlot(686.16f, 195.999f, 22.036f, 33.522f, scale))
        Artwork(R.drawable.logo_dash_right_lower, Modifier.brandSlot(1000f, 384f, 23.001f, 19.001f, scale))
        Artwork(R.drawable.logo_dash_right_upper, Modifier.brandSlot(1007f, 363.341f, 25.81f, 10.179f, scale))
    }
}

private fun Modifier.brandSlot(x: Float, y: Float, width: Float, height: Float, scale: Float) =
    offset(((x - 650) * scale).dp, ((y - 195) * scale).dp).size((width * scale).dp, (height * scale).dp)

@Composable
private fun Artwork(resource: Int, modifier: Modifier) {
    Image(painterResource(resource), contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds)
}

private data class Character(
    val body: Int,
    val x: Float,
    val y: Float,
    val extent: Float,
    val eyes: List<Offset>,
    val mouth: Offset,
    val eyeAngle: Float = 0f,
    val gaze: Offset? = null,
    val verticalMouth: Boolean = false,
    val initiallyHappy: Boolean = false,
    val smileMouth: Boolean = false,
)

private val Characters = listOf(
    Character(R.drawable.character_yellow, 979f, 544f, 420.392f, listOf(Offset(1120.5f, 666f), Offset(1177.5f, 666f)), Offset(1149.15f, 731.95f), initiallyHappy = true),
    Character(R.drawable.character_pink, 660f, 578f, 373.242f, listOf(Offset(833.62f, 674.87f), Offset(891.09f, 672.95f)), Offset(862.5f, 730f), -2.847f, smileMouth = true),
    Character(R.drawable.character_blue, 1279f, 441f, 420.392f, listOf(Offset(1404.41f, 576.55f), Offset(1461.22f, 588.23f)), Offset(1413.15f, 655.95f), 11.616f, Offset(-10.5f, -5f)),
    Character(R.drawable.character_purple, 321f, 470f, 420.392f, listOf(Offset(558.16f, 601.23f), Offset(614.97f, 589.55f)), Offset(615.39f, 674.88f), -11.616f, Offset(10.5f, -10f), verticalMouth = true),
    Character(R.drawable.character_green, 43f, 512f, 420.392f, listOf(Offset(264.89f, 634f), Offset(321.89f, 634f)), Offset(293.15f, 694.95f), verticalMouth = true, initiallyHappy = true),
)

@Composable
private fun FloatingCharacter(character: Character, artScale: Float, animate: Boolean, modifier: Modifier) {
    val bob = remember { Animatable(0f) }
    val eyes = remember { Animatable(1f) }
    val happy = remember { Animatable(if (character.initiallyHappy) 1f else 0f) }
    val density = LocalDensity.current
    LaunchedEffect(animate) {
        if (!animate) {
            bob.snapTo(0f)
            eyes.snapTo(1f)
            happy.snapTo(if (character.initiallyHappy) 1f else 0f)
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                delay(Random.nextLong(0, 1_500))
                var direction = if (Random.nextBoolean()) 1f else -1f
                while (isActive) {
                    bob.animateTo(
                        direction * Random.nextDouble(0.75, 1.0).toFloat(),
                        tween(Random.nextInt(3_800, 5_201), easing = EaseInOutSine),
                    )
                    direction = -direction
                }
            }
            launch {
                while (isActive) {
                    delay(Random.nextLong(2_500, 6_001))
                    if (happy.value > 0f) continue
                    eyes.animateTo(0.08f, tween(70))
                    delay(50)
                    eyes.animateTo(1f, tween(100))
                }
            }
            launch {
                if (character.initiallyHappy) delay(Random.nextLong(800, 1_801))
                happy.animateTo(0f, tween(150))
                while (isActive) {
                    delay(Random.nextLong(5_000, 12_001))
                    if (eyes.value < 1f) continue
                    happy.animateTo(1f, tween(150))
                    delay(Random.nextLong(800, 1_801))
                    happy.animateTo(0f, tween(150))
                }
            }
        }
    }
    val amplitude = minOf(356 * artScale * 0.06f, 24f)
    Box(modifier.size((character.extent * artScale).dp).graphicsLayer {
        translationY = with(density) { (bob.value * amplitude).dp.toPx() }
    }.testTag("character-${character.body}")) {
        Artwork(character.body, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            scale(size.width / character.extent, pivot = Offset.Zero) {
                translate(-character.x, -character.y) {
                    character.eyes.forEach { center ->
                        rotate(character.eyeAngle, center) {
                            scale(1f, eyes.value, center) {
                                if (character.gaze != null) {
                                    drawRoundRect(Color(0xFFF7F8FA).copy(alpha = 1 - happy.value), center - Offset(29f, 38.5f), Size(58f, 77f), CornerRadius(29f))
                                }
                                val pupil = center + (character.gaze ?: Offset.Zero)
                                drawRoundRect(Ink.copy(alpha = 1 - happy.value), pupil - Offset(16.0735f, 21.24f), Size(32.147f, 42.48f), CornerRadius(29f))
                            }
                        }
                    }
                    if (!character.smileMouth) {
                        val mouthSize = if (character.verticalMouth) Size(19.896f, 26.292f) else Size(26.292f, 19.896f)
                        rotate(if (character.verticalMouth) character.eyeAngle else 0f, character.mouth) {
                            drawRoundRect(Ink.copy(alpha = if (character.initiallyHappy) 1f else 1 - happy.value), character.mouth - Offset(mouthSize.width / 2, mouthSize.height / 2), mouthSize, CornerRadius(29f))
                        }
                    }
                }
            }
        }
        character.eyes.forEach { center ->
            Artwork(
                if (character.body == R.drawable.character_green) R.drawable.eye_happy_green else R.drawable.eye_happy_yellow,
                Modifier.offset(((center.x - 33.5f - character.x) * artScale).dp, ((center.y - 19f - character.y) * artScale).dp)
                    .size((67 * artScale).dp, (38 * artScale).dp).graphicsLayer { alpha = happy.value },
            )
        }
        Artwork(
            R.drawable.mouth_smile,
            Modifier.offset(((character.mouth.x - 21.5f - character.x) * artScale).dp, ((character.mouth.y - 13f - character.y) * artScale).dp)
                .size((43 * artScale).dp, (26 * artScale).dp).graphicsLayer {
                    alpha = if (character.smileMouth) 1f else if (character.initiallyHappy) 0f else happy.value
                },
        )
    }
}

@Preview(name = "Landscape", widthDp = 850, heightDp = 592)
@Preview(name = "Portrait", widthDp = 360, heightDp = 640)
@Preview(name = "Short landscape", widthDp = 640, heightDp = 320)
@Composable
private fun MainScreenPreview() {
    MainScreen(onStart = {}, onArchive = {}, animationsEnabled = false)
}
