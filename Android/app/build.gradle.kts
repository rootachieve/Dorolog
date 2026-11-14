import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val dorologLocalProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.forever.dorolog"
    useLibrary("android.car")
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.forever.dorolog"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.forever.dorolog.DorologTestRunner"
        val voiceUrl = providers.gradleProperty("dorolog.voiceUrl").orElse("").get()
        require(voiceUrl.isEmpty() || voiceUrl.startsWith("wss://")) { "dorolog.voiceUrl must use wss://" }
        buildConfigField("String", "DRIVING_VOICE_URL", "\"${voiceUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        val apiBaseUrl = providers.gradleProperty("dorolog.apiBaseUrl").orElse("").get()
        require(apiBaseUrl.isEmpty() || URI(apiBaseUrl).let {
            it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null &&
                it.query == null && it.fragment == null && apiBaseUrl.endsWith('/')
        }) { "dorolog.apiBaseUrl must be an HTTPS base URL ending with /" }
        buildConfigField("String", "TRIP_API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        val kakaoMapKey = providers.gradleProperty("dorolog.kakaoMapNativeAppKey")
            .orElse(dorologLocalProperties.getProperty("dorolog.kakaoMapNativeAppKey", "")).get()
        buildConfigField("String", "KAKAO_MAP_NATIVE_APP_KEY", "\"${kakaoMapKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.pleos.navi)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)
    implementation(libs.compose.audiowaveform)
    implementation(libs.okhttp)
    implementation(libs.kakao.maps)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
