package com.forever.dorolog

import org.junit.Assert.*
import org.junit.Test

class TripSummaryLayoutTest {
    @Test fun oneStory_isCentered_andUpToFiveFillTheRail() {
        assertEquals(emptyList<Float>(), summaryStoryCenters(0, 1000f, 100f))
        assertEquals(listOf(500f), summaryStoryCenters(1, 1000f, 100f))
        assertEquals(listOf(100f, 900f), summaryStoryCenters(2, 1000f, 100f))
        assertEquals(listOf(100f, 500f, 900f), summaryStoryCenters(3, 1000f, 100f))
        assertEquals(listOf(100f, 300f, 500f, 700f, 900f), summaryStoryCenters(5, 1000f, 100f))
    }

    @Test fun sixthStory_overflows_withoutCompressingTheFirstFive() {
        assertEquals(listOf(100f, 300f, 500f, 700f, 900f, 1100f),
            summaryStoryCenters(6, 1000f, 100f))
    }

    @Test fun selection_staysOnlyWhileCenterIsInsideDetailWidth() {
        assertTrue(summarySelectionVisible(64f, 64f, 1636f))
        assertTrue(summarySelectionVisible(1636f, 64f, 1636f))
        assertFalse(summarySelectionVisible(63f, 64f, 1636f))
        assertFalse(summarySelectionVisible(1637f, 64f, 1636f))
    }
}
