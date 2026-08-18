package com.modose.app.ar.plane

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalPlaneStateTrackerTest {
    @Test
    fun maintainsTrackingForCurrentSelection() {
        val tracker = HorizontalPlaneStateTracker()
        val selected = plane(7)

        assertEquals(HorizontalPlaneState.Tracking(selected), tracker.update(selected))
        assertEquals(HorizontalPlaneState.Tracking(selected), tracker.update(selected))
    }

    @Test
    fun reportsLostOnceThenReturnsToSearching() {
        val tracker = HorizontalPlaneStateTracker()
        tracker.update(plane(7))

        assertEquals(HorizontalPlaneState.Lost(7), tracker.update(null))
        assertEquals(HorizontalPlaneState.Searching, tracker.update(null))
    }

    @Test
    fun resetClearsPreviousSelectionWithoutLostEvent() {
        val tracker = HorizontalPlaneStateTracker()
        tracker.update(plane(7))

        tracker.reset()

        assertEquals(HorizontalPlaneState.Searching, tracker.update(null))
    }

    private fun plane(id: Long) = SelectedHorizontalPlane(
        id = id,
        distanceMeters = 1f,
        extentXMeters = 0.8f,
        extentZMeters = 0.6f,
    )
}
