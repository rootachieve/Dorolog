package com.forever.dorolog

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener

// Guidance mode, not isRouting alone, identifies an active destination.
internal fun navigationDestinationPresence(mode: NaviMode, routing: Boolean): Boolean? = when {
    mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING -> true
    routing -> null
    else -> false
}

// The Android 14 Pleos emulator requires the pre-36 property callback APIs.
@Suppress("DEPRECATION")
internal class DepartureVehicleSource(
    private val context: Context,
    private val onNavigation: (Destination?, Boolean) -> Unit,
    private val onSpeed: (Float?) -> Unit,
    private val onArrived: (Destination?) -> Unit = {},
    private val onSafety: (blocked: Boolean, available: Boolean) -> Unit = { _, _ -> },
    private val onParked: (Boolean?) -> Unit = {},
    private val onLocation: (VehicleLocation) -> Unit = {},
    private val onDestinationSet: () -> Unit = {},
    private val onDestinationConfirmed: (Destination?) -> Unit = {},
    private val observeVehicle: Boolean = true,
) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var navi: NaviHelper? = null
    private var properties: CarPropertyManager? = null
    private var propertyListener: CarPropertyManager.CarPropertyEventCallback? = null
    private var addressPoll: Runnable? = null
    private var naviListener: NaviHelperEventListener? = null
    private var destinationChangePending = false
    private var currentDestination: Destination? = null
    private var vehicle: Vehicle? = null
    private val safetyCleanup = mutableListOf<() -> Unit>()

    fun start(car: Car?) {
        stop()
        onLocation(VehicleLocation())
        val token = generation
        fun deliver(action: () -> Unit) {
            // NaviHelper invokes listeners on Binder threads; serialize with UI and timer events.
            main.post { if (generation == token) action() }
        }
        if (observeVehicle) startSafety(::deliver)
        try {
            val helper = NaviHelper(context)
            navi = helper
            val listener = object : NaviHelperEventListener {
                override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = deliver {
                    when (navigationDestinationPresence(routeStateInfo.naviMode, routeStateInfo.isRouting)) {
                        true -> refreshDestination()
                        null -> {
                            destinationChangePending = true
                            currentDestination = null
                            onDestinationSet()
                            onNavigation(null, false)
                        }
                        false -> {
                            currentDestination = null
                            destinationChangePending = false
                            onDestinationConfirmed(null)
                            onNavigation(null, true)
                        }
                    }
                }
                override fun onDestinationInfo(destinationInfo: DestinationInfo) = deliver {
                    destinationChangePending = false
                    val destination = Destination(destinationInfo.name.ifBlank { destinationInfo.address },
                        destinationInfo.latitude, destinationInfo.longitude, destinationInfo.address)
                    currentDestination = destination
                    onDestinationConfirmed(destination)
                    onNavigation(destination, true)
                }
                override fun onDrivingInfo(drivingInfo: DrivingInfo) = deliver {
                    // A queued driving update can still describe the route that just ended.
                    if (destinationChangePending) return@deliver
                    val destination = drivingInfo.destination
                    if (currentDestination?.name == destination.name) onNavigation(currentDestination, true)
                    else refreshDestination()
                }
                override fun onDestinationChanged() = deliver {
                    destinationChangePending = true
                    currentDestination = null
                    onDestinationSet()
                    onNavigation(null, false)
                    refreshRoute()
                }
                override fun onRoutingCompleted() = deliver { refreshRoute() }
                override fun onRouteCancelled() = deliver {
                    currentDestination = null
                    destinationChangePending = false
                    onDestinationConfirmed(null)
                    onNavigation(null, true)
                }
                override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
                    deliver {
                        onArrived(destinationArrivedInfo.name.takeIf { it.isNotBlank() }?.let(::Destination))
                    }
                override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = deliver {
                    val latitude = currentLocationInfo.latitude
                    val longitude = currentLocationInfo.longitude
                    val validCoordinates = latitude.isFinite() && longitude.isFinite() &&
                        latitude in -90.0..90.0 && longitude in -180.0..180.0
                    onLocation(VehicleLocation(
                        address = currentLocationInfo.address.trim().takeIf { it.isNotEmpty() },
                        latitude = latitude.takeIf { validCoordinates },
                        longitude = longitude.takeIf { validCoordinates },
                    ))
                }
                override fun onError(errorCode: NaviErrorCode) = deliver {
                    onLocation(VehicleLocation())
                    onNavigation(null, false)
                }
            }
            naviListener = listener
            helper.initialize(
                onConnected = { deliver {
                    helper.addListener(listener)
                    refreshRoute()
                    addressPoll?.let(main::removeCallbacks)
                    val poll = object : Runnable {
                        override fun run() {
                            if (generation != token) return
                            try { helper.getCurrentLocationInfo() } catch (exception: RuntimeException) {
                                Log.w("Departure", "Current navigation address unavailable", exception)
                                onLocation(VehicleLocation())
                            }
                            main.postDelayed(this, 10_000L)
                        }
                    }
                    addressPoll = poll
                    poll.run()
                } },
                onFailed = { deliver { onLocation(VehicleLocation()); onNavigation(null, false) } },
            )
        } catch (exception: RuntimeException) {
            Log.w("Departure", "Navigation service unavailable", exception)
            onLocation(VehicleLocation())
            onNavigation(null, false)
        }

        if (!observeVehicle) return
        if (car == null) {
            publishSpeed(null)
            onParked(null)
            return
        }
        try {
            val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager == null) {
                publishSpeed(null)
                onParked(null)
                return
            }
            properties = manager
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) = deliver {
                    val available = value.status == CarPropertyValue.STATUS_AVAILABLE
                    when (value.propertyId) {
                        VehiclePropertyIds.PERF_VEHICLE_SPEED -> publishSpeed(if (available) value.value as? Float else null)
                        VehiclePropertyIds.GEAR_SELECTION -> publishGear(if (available) value.value as? Int else null)
                    }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) = deliver {
                    if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) publishSpeed(null)
                    if (propertyId == VehiclePropertyIds.GEAR_SELECTION) onParked(null)
                }
            }
            propertyListener = callback
            try {
                if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED ||
                    manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) == null ||
                    !manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)) {
                    publishSpeed(null)
                }
            } catch (exception: RuntimeException) {
                Log.w("Departure", "Vehicle speed unavailable", exception)
                publishSpeed(null)
            }
            try {
                if (context.checkSelfPermission(Car.PERMISSION_POWERTRAIN) != PackageManager.PERMISSION_GRANTED ||
                    manager.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) == null ||
                    !manager.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                    onParked(null)
                } else {
                    val initial = manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
                    publishGear(if (initial?.status == CarPropertyValue.STATUS_AVAILABLE) initial.value else null)
                }
            } catch (exception: RuntimeException) {
                Log.w("Departure", "Vehicle gear unavailable", exception)
                onParked(null)
            }
        } catch (exception: RuntimeException) {
            Log.w("Departure", "Vehicle properties unavailable", exception)
            publishSpeed(null)
            onParked(null)
        }
    }

    private fun publishSpeed(value: Float?) {
        val speed = value?.takeIf { it.isFinite() }
        onSpeed(speed)
    }

    private fun publishGear(value: Int?) {
        onParked(value?.takeIf { it != VehicleGear.GEAR_UNKNOWN }?.let { it == VehicleGear.GEAR_PARK })
    }

    private fun startSafety(deliver: (() -> Unit) -> Unit) {
        val belts = mutableMapOf<SeatArea, Boolean?>()
        val doors = mutableMapOf<DoorArea, Boolean?>()
        var pocBeltWarning: Boolean? = null
        fun report() {
            val available = (pocBeltWarning != null || (belts.isNotEmpty() && belts.values.all { it != null })) &&
                doors.isNotEmpty() && doors.values.all { it != null }
            // A driving session stays blocked until the warning clears, including while stopped.
            onSafety(pocBeltWarning == true || belts.values.any { it == true } || doors.values.any { it == true }, available)
        }
        report()
        try {
            val connection = Vehicle(context)
            vehicle = connection
            connection.initialize()
            val safety = connection.getSafety()
            safety.checkSeatNotBuckledWarningCapabilities(
                onComplete = { config, areas -> deliver {
                    if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) return@deliver
                    areas.forEach { area ->
                        belts[area] = null
                        var revision = 0
                        val listener = object : SeatBeltNotBuckledWarningListener {
                            override fun onSeatBeltNotBuckledWarningUpdated(value: Boolean?) = deliver {
                                revision++
                                belts[area] = value; report()
                            }
                            override fun onFailed(exception: Exception) = deliver {
                                revision++
                                belts[area] = null; report()
                            }
                        }
                        try {
                            safety.registerSeatBeltNotBuckledWarning(area, listener)
                            safetyCleanup += { safety.unregisterSeatBeltNotBuckledWarning(area, listener) }
                            safety.hasSeatBeltNotBuckledWarning(area,
                                onComplete = { value -> deliver { if (revision == 0) { belts[area] = value; report() } } },
                                onFailed = { deliver { if (revision == 0) { belts[area] = null; report() } } },
                            )
                        } catch (exception: RuntimeException) {
                            Log.w("Departure", "Seat belt warning unavailable", exception)
                        }
                    }
                    report()
                } },
                onFailed = { exception -> deliver {
                    Log.w("Departure", "Seat belt warning unsupported or unavailable", exception)
                    safetyCleanup += subscribePocSeatWarning(context, safety, deliver) {
                        pocBeltWarning = it
                        report()
                    }
                    report()
                } },
            )
            val door = connection.getDoor()
            door.checkDoorPositionCapabilities(
                onComplete = { config, areas -> deliver {
                    if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) return@deliver
                    areas.forEach { (area, bounds) ->
                        doors[area] = null
                        var revision = 0
                        fun update(value: Int?) {
                            doors[area] = value?.takeIf { it in bounds.minValue..bounds.maxValue }?.let { it != 0 }
                            report()
                        }
                        val listener = object : DoorPositionListener {
                            override fun onPositionUpdated(value: Int?) = deliver { revision++; update(value) }
                            override fun onFailed(exception: Exception) = deliver { revision++; update(null) }
                        }
                        try {
                            door.registerDoorPosition(area, listener)
                            safetyCleanup += { door.unregisterDoorPosition(area, listener) }
                            door.getCurrentDoorPosition(area,
                                onComplete = { value -> deliver { if (revision == 0) update(value) } },
                                onFailed = { deliver { if (revision == 0) update(null) } },
                            )
                        } catch (exception: RuntimeException) {
                            Log.w("Departure", "Door position unavailable", exception)
                        }
                    }
                    report()
                } },
                onFailed = { exception -> deliver {
                    Log.w("Departure", "Door position unsupported or unavailable", exception)
                    report()
                } },
            )
        } catch (exception: RuntimeException) {
            Log.w("Departure", "Public vehicle safety service unavailable", exception)
            report()
        }
    }

    private fun refreshRoute() {
        try { navi?.getRouteStateInfo() } catch (_: RuntimeException) { onNavigation(null, false) }
    }

    private fun refreshDestination() {
        try { navi?.getDestinationInfo() } catch (_: RuntimeException) { onNavigation(null, false) }
    }

    fun stop() {
        generation++
        destinationChangePending = false
        currentDestination = null
        addressPoll?.let(main::removeCallbacks)
        addressPoll = null
        safetyCleanup.forEach { cleanup ->
            try { cleanup() } catch (exception: RuntimeException) {
                Log.w("Departure", "Safety service disconnected before cleanup", exception)
            }
        }
        safetyCleanup.clear()
        try { vehicle?.release() } catch (exception: RuntimeException) {
            Log.w("Departure", "Vehicle service disconnected before cleanup", exception)
        } finally { vehicle = null }
        try {
            propertyListener?.let { properties?.unregisterCallback(it) }
        } catch (exception: RuntimeException) {
            Log.w("Departure", "Speed service disconnected before cleanup", exception)
        } finally {
            propertyListener = null
            properties = null
        }
        try {
            try { naviListener?.let { navi?.removeListener(it) } }
            finally { navi?.release() }
        } catch (exception: RuntimeException) {
            Log.w("Departure", "Navigation service disconnected before cleanup", exception)
        } finally { navi = null; naviListener = null }
    }
}
