package com.forever.dorolog

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

// The Android 14 Pleos emulator requires the pre-36 property callback APIs.
@Suppress("DEPRECATION")
internal class DepartureVehicleSource(
    private val context: Context,
    private val onNavigation: (Destination?, Boolean) -> Unit,
    private val onSpeed: (Float?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var navi: NaviHelper? = null
    private var properties: CarPropertyManager? = null
    private var speedListener: CarPropertyManager.CarPropertyEventCallback? = null

    fun start(car: Car?) {
        stop()
        val token = generation
        fun deliver(action: () -> Unit) {
            // NaviHelper invokes listeners on Binder threads; serialize with UI and timer events.
            main.post { if (generation == token) action() }
        }
        try {
            val helper = NaviHelper(context)
            navi = helper
            val listener = object : NaviHelperEventListener {
                override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = deliver {
                    if (routeStateInfo.isRouting) refreshDestination() else onNavigation(null, true)
                }
                override fun onDestinationInfo(destinationInfo: DestinationInfo) = deliver {
                    onNavigation(destinationInfo.name.takeIf { it.isNotBlank() }?.let {
                        Destination(it)
                    }, true)
                }
                override fun onDrivingInfo(drivingInfo: DrivingInfo) = deliver {
                    val destination = drivingInfo.destination
                    onNavigation(destination.name.takeIf { it.isNotBlank() }?.let {
                        Destination(it)
                    }, true)
                }
                override fun onDestinationChanged() = deliver { refreshRoute() }
                override fun onRoutingCompleted() = deliver { refreshRoute() }
                override fun onRouteCancelled() = deliver { onNavigation(null, true) }
                override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
                    deliver { onNavigation(null, true) }
                override fun onError(errorCode: NaviErrorCode) = deliver { onNavigation(null, false) }
            }
            helper.initialize(
                onConnected = { deliver { helper.addListener(listener); refreshRoute() } },
                onFailed = { deliver { onNavigation(null, false) } },
            )
        } catch (exception: RuntimeException) {
            Log.w("Departure", "Navigation service unavailable", exception)
            onNavigation(null, false)
        }

        if (car == null || context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            onSpeed(null)
            return
        }
        try {
            val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager?.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) == null) {
                onSpeed(null)
                return
            }
            properties = manager
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) = deliver {
                    if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                        onSpeed(if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value as? Float else null)
                    }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) = deliver {
                    if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) onSpeed(null)
                }
            }
            speedListener = callback
            if (!manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)) {
                onSpeed(null)
            }
        } catch (exception: RuntimeException) {
            Log.w("Departure", "Vehicle speed unavailable", exception)
            onSpeed(null)
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
        try {
            speedListener?.let { properties?.unregisterCallback(it) }
        } catch (exception: RuntimeException) {
            Log.w("Departure", "Speed service disconnected before cleanup", exception)
        } finally {
            speedListener = null
            properties = null
        }
        try { navi?.release() } catch (exception: RuntimeException) {
            Log.w("Departure", "Navigation service disconnected before cleanup", exception)
        } finally { navi = null }
    }
}
