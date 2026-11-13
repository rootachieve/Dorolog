package com.forever.dorolog

import ai.pleos.playground.navi.constants.NaviMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DepartureVehicleSourceTest {
    @Test fun guidanceModeDeterminesDestination_whileAmbiguousRoutingRemainsUnknown() {
        val cases: List<Triple<NaviMode, Boolean, Boolean?>> = listOf(
            Triple(NaviMode.NOT_WORKING, false, false),
            Triple(NaviMode.SAFE_GUIDING, false, false),
            Triple(NaviMode.ROUTE_GUIDING, false, true),
            Triple(NaviMode.SIMULATION_GUIDING, false, true),
            Triple(NaviMode.NOT_WORKING, true, null),
            Triple(NaviMode.SAFE_GUIDING, true, null),
            Triple(NaviMode.ROUTE_GUIDING, true, true),
            Triple(NaviMode.SIMULATION_GUIDING, true, true),
        )
        for ((mode, routing, expected) in cases) {
            assertEquals("$mode, routing=$routing", expected, navigationDestinationPresence(mode, routing))
        }
    }
}
