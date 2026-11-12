package com.forever.dorolog

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.vehicle.api.Safety
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener

// shortcut: emulator POC only; production needs Pleos signature/privileged approval.
internal fun subscribePocSeatWarning(
    context: Context,
    safety: Safety,
    deliver: (() -> Unit) -> Unit,
    onWarning: (Boolean?) -> Unit,
): () -> Unit {
    if (context.checkSelfPermission("pleos.car.permission.PRIVATE_CAR_SAFETY") != PackageManager.PERMISSION_GRANTED) {
        return {}
    }
    var revision = 0
    var registered = false
    fun update(snapshot: Telltales?) {
        onWarning(snapshot?.telltales?.any {
            it.type == TelltaleType.SEAT_BELT &&
                (it.state == TelltaleState.ON || it.state == TelltaleState.BLINK)
        })
    }
    val listener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) = deliver {
            revision++
            update(telltales)
        }
        override fun onFailed(exception: Exception) = deliver {
            revision++
            Log.w("Departure", "POC seat warning subscription unavailable", exception)
            onWarning(null)
        }
    }
    try {
        safety.registerTelltales(listener)
        registered = true
        safety.getTelltalesInfo(
            onComplete = { snapshot -> deliver {
                // A late initial read must not replace a newer event or subscription failure.
                if (revision == 0) update(snapshot)
            } },
            onFailed = { exception -> deliver {
                if (revision == 0) {
                    Log.w("Departure", "POC seat warning snapshot unavailable", exception)
                    onWarning(null)
                }
            } },
        )
    } catch (exception: RuntimeException) {
        Log.w("Departure", "POC seat warning unavailable", exception)
        deliver { onWarning(null) }
    }
    return { if (registered) safety.unregisterTelltales(listener) }
}
