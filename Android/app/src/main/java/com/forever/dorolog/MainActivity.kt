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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
                archiveBrowsingAllowed = !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) || carAllowsAnimation,
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

private enum class Screen { Main, TripSelection, Departure, Archive }

@Composable
internal fun DorologApp(
    onBackground: () -> Unit,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = true,
    car: Car? = null,
    transitionAnimationsEnabled: Boolean = animationsEnabled,
    selectedTripId: String? = null,
    archiveBrowsingAllowed: Boolean = true,
    archiveRepository: ArchiveRepository? = null,
    summaryRepository: TripSummaryRepository? = null,
) {
    var screen by remember { mutableStateOf(Screen.Main) }
    var activeTripId by remember { mutableStateOf<String?>(null) }
    val returnHome = { activeTripId = null; screen = Screen.Main }
    BackHandler {
        if (screen == Screen.Main) onBackground() else returnHome()
    }
    when (screen) {
        Screen.Main -> MainScreen(
            onStart = { screen = Screen.TripSelection },
            onArchive = { screen = Screen.Archive },
            modifier = modifier,
            animationsEnabled = animationsEnabled,
        )
        Screen.TripSelection -> TripSelectionScreen(
            selectedTripId = selectedTripId,
            onBack = returnHome,
            onTripSelected = { id ->
                require(id.isNotBlank())
                activeTripId = id
                screen = Screen.Departure
            },
            modifier = modifier,
        )
        Screen.Departure -> key(activeTripId) {
            DepartureRoute(tripId = checkNotNull(activeTripId), onBack = returnHome,
                modifier = modifier, animationsEnabled = transitionAnimationsEnabled, car = car,
                summaryAnimationsEnabled = animationsEnabled,
                summaryBrowsingAllowed = archiveBrowsingAllowed,
                summaryRepository = summaryRepository)
        }
        Screen.Archive -> ArchiveBrowser(
            onHome = returnHome, modifier = modifier, browsingAllowed = archiveBrowsingAllowed,
            animationsEnabled = animationsEnabled,
            repository = archiveRepository, summaryRepository = summaryRepository,
        )
    }
}

@Composable
private fun ArchiveBrowser(
    onHome: () -> Unit,
    modifier: Modifier,
    browsingAllowed: Boolean,
    animationsEnabled: Boolean,
    repository: ArchiveRepository?,
    summaryRepository: TripSummaryRepository?,
) {
    val owner = remember {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    androidx.compose.runtime.DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val archiveViewModel = androidx.lifecycle.viewmodel.compose.viewModel<ArchiveViewModel>(
        viewModelStoreOwner = owner,
        factory = viewModelFactory { initializer { ArchiveViewModel(repository ?: ArchiveRepository()) } },
    )
    var selectedTrip by remember { mutableStateOf<ArchiveTrip?>(null) }
    val stateHolder = rememberSaveableStateHolder()
    BackHandler { if (selectedTrip == null) onHome() else selectedTrip = null }
    val trip = selectedTrip
    if (trip == null) {
        stateHolder.SaveableStateProvider("archive") {
            ArchiveRoute(onBack = onHome, onTripClick = { selectedTrip = it }, modifier = modifier,
                browsingAllowed = browsingAllowed, viewModel = archiveViewModel)
        }
    } else {
        TripSummaryScreen(tripId = trip.id, destinationName = trip.destinationName,
            onDone = onHome, onBack = { selectedTrip = null }, modifier = modifier,
            animationsEnabled = animationsEnabled, browsingAllowed = browsingAllowed,
            repository = summaryRepository)
    }
}
