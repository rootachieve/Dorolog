package com.forever.dorolog

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KakaoMapSdkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configuredNativeKeyAuthenticatesMapSdk() {
        assumeTrue("A local Kakao native key is required", BuildConfig.KAKAO_MAP_NATIVE_APP_KEY.isNotBlank())
        val completed = CountDownLatch(1)
        val ready = AtomicBoolean(false)
        val error = AtomicReference<String?>(null)
        compose.setContent {
            val context = LocalContext.current
            val map = remember {
                KakaoMapSdk.init(context.applicationContext, BuildConfig.KAKAO_MAP_NATIVE_APP_KEY)
                MapView(context)
            }
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
            DisposableEffect(map) {
                map.start(object : MapLifeCycleCallback() {
                    override fun onMapDestroy() = Unit
                    override fun onMapError(exception: Exception) {
                        error.set(exception.toString().replace(BuildConfig.KAKAO_MAP_NATIVE_APP_KEY, "[redacted]"))
                        completed.countDown()
                    }
                }, object : KakaoMapReadyCallback() {
                    override fun onMapReady(kakaoMap: KakaoMap) {
                        ready.set(true)
                        completed.countDown()
                    }
                })
                map.setFinishManually(true)
                map.resume()
                onDispose { map.pause(); map.finish() }
            }
        }
        assertTrue("Kakao SDK readiness callback timed out", completed.await(30, TimeUnit.SECONDS))
        assertTrue("Kakao SDK authentication failed: ${error.get()}", ready.get())
        // shortcut: SDK has no tile-ready callback; this delay is only for the screenshot evidence.
        SystemClock.sleep(1_500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        if (screenshot != null) {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
            File(directory, "kakao-map-sdk.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }
}
