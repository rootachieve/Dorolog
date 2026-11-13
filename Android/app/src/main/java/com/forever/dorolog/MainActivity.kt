package com.forever.dorolog

import android.animation.ValueAnimator
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private var resumed by mutableStateOf(false)
    private var systemAnimationsEnabled by mutableStateOf(false)
    private var carAllowsAnimation by mutableStateOf(false)
    private var car: Car? = null
    private var connectedCar by mutableStateOf<Car?>(null)
    private var restrictionsManager: CarUxRestrictionsManager? = null
    private val animationScaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            systemAnimationsEnabled = ValueAnimator.areAnimatorsEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DorologApp(
                onBackground = { moveTaskToBack(true) },
                animationsEnabled = resumed && systemAnimationsEnabled && carAllowsAnimation,
                transitionAnimationsEnabled = resumed && systemAnimationsEnabled,
                car = connectedCar,
            )
        }
        connectCar()
    }

    override fun onResume() {
        super.onResume()
        systemAnimationsEnabled = ValueAnimator.areAnimatorsEnabled()
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            animationScaleObserver,
        )
        resumed = true
    }

    override fun onPause() {
        resumed = false
        contentResolver.unregisterContentObserver(animationScaleObserver)
        super.onPause()
    }

    private fun connectCar() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                carAllowsAnimation = false
                this.connectedCar = if (ready && !isDestroyed) connectedCar else null
                restrictionsManager = null
                if (!ready || isDestroyed) return@createCar
                try {
                    val manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                        as? CarUxRestrictionsManager ?: return@createCar
                    restrictionsManager = manager
                    manager.registerListener { restrictions ->
                        carAllowsAnimation = !restrictions.isRequiresDistractionOptimization
                    }
                    carAllowsAnimation = manager.currentCarUxRestrictions
                        ?.isRequiresDistractionOptimization == false
                } catch (exception: RuntimeException) {
                    Log.w("Dorolog", "Vehicle restrictions unavailable", exception)
                }
            }
        } catch (exception: RuntimeException) {
            Log.w("Dorolog", "Vehicle service unavailable", exception)
        }
    }

    override fun onDestroy() {
        carAllowsAnimation = false
        try {
            restrictionsManager?.unregisterListener()
        } catch (exception: RuntimeException) {
            Log.w("Dorolog", "Vehicle service disconnected before cleanup", exception)
        } finally {
            car?.disconnect()
            restrictionsManager = null
            car = null
        }
        super.onDestroy()
    }
}

private enum class Screen { Main, Departure, Archive }

@Composable
internal fun DorologApp(
    onBackground: () -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
    car: Car? = null,
    transitionAnimationsEnabled: Boolean = animationsEnabled,
    selectedTripId: String? = null,
) {
    var screen by remember { mutableStateOf(Screen.Main) }
    var activeTripId by remember { mutableStateOf<String?>(null) }
    val tripId = selectedTripId?.takeIf { it.isNotBlank() }
    val returnHome = { activeTripId = null; screen = Screen.Main }
    BackHandler {
        if (screen == Screen.Main) onBackground() else returnHome()
    }
    when (screen) {
        Screen.Main -> MainScreen(
            onStart = { if (tripId != null) { activeTripId = tripId; screen = Screen.Departure } },
            onArchive = { screen = Screen.Archive },
            modifier = modifier,
            animationsEnabled = animationsEnabled,
            startEnabled = tripId != null,
        )
        Screen.Departure -> key(activeTripId) {
            DepartureRoute(tripId = checkNotNull(activeTripId), onBack = returnHome,
                modifier = modifier, animationsEnabled = transitionAnimationsEnabled, car = car)
        }
        Screen.Archive -> ArchiveScreen(onBack = returnHome, modifier = modifier)
    }
}
