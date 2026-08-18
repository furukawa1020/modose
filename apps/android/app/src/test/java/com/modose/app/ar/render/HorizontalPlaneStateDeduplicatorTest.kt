package com.modose.app.ar.render

import com.modose.app.ar.plane.HorizontalPlaneState
import com.modose.app.ar.plane.SelectedHorizontalPlane
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalPlaneStateDeduplicatorTest {
    @Test
    fun `emits only changed plane states`() {
        val deduplicator = HorizontalPlaneStateDeduplicator()
        val tracking = HorizontalPlaneState.Tracking(
            SelectedHorizontalPlane(id = 7L, distanceMeters = 0.4f),
        )

        assertTrue(deduplicator.shouldEmit(HorizontalPlaneState.Searching))
        assertFalse(deduplicator.shouldEmit(HorizontalPlaneState.Searching))
        assertTrue(deduplicator.shouldEmit(tracking))
        assertFalse(deduplicator.shouldEmit(tracking))
    }

    @Test
    fun `reset allows current state to be emitted again`() {
        val deduplicator = HorizontalPlaneStateDeduplicator()
        deduplicator.shouldEmit(HorizontalPlaneState.Searching)

        deduplicator.reset()

        assertTrue(deduplicator.shouldEmit(HorizontalPlaneState.Searching))
    }
}
