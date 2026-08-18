package com.modose.app.ar.plane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HorizontalPlaneSelectionPolicyTest {
    @Test
    fun selectsNearestValidCenterPlane() {
        val selected = HorizontalPlaneSelectionPolicy.select(
            listOf(
                candidate(id = 1, distance = 1.2f),
                candidate(id = 2, distance = 0.7f),
            ),
        )

        assertEquals(2L, selected?.id)
        assertEquals(0.7f, selected?.distanceMeters)
    }

    @Test
    fun rejectsNonHorizontalPausedSubsumedAndOffCenterPlanes() {
        assertNull(
            HorizontalPlaneSelectionPolicy.select(
                listOf(
                    candidate(id = 1, isUpward = false),
                    candidate(id = 2, isTracking = false),
                    candidate(id = 3, isSubsumed = true),
                    candidate(id = 4, containsCenter = false),
                ),
            ),
        )
    }

    @Test
    fun rejectsInvalidDistanceAndExtent() {
        assertNull(
            HorizontalPlaneSelectionPolicy.select(
                listOf(
                    candidate(id = 1, distance = Float.NaN),
                    candidate(id = 2, distance = -0.1f),
                    candidate(id = 3, extentX = 0f),
                    candidate(id = 4, extentZ = Float.POSITIVE_INFINITY),
                ),
            ),
        )
    }

    private fun candidate(
        id: Long,
        distance: Float = 1f,
        extentX: Float = 0.8f,
        extentZ: Float = 0.6f,
        isUpward: Boolean = true,
        isTracking: Boolean = true,
        isSubsumed: Boolean = false,
        containsCenter: Boolean = true,
    ) = HorizontalPlaneCandidate(
        id = id,
        distanceMeters = distance,
        extentXMeters = extentX,
        extentZMeters = extentZ,
        isUpwardFacing = isUpward,
        isTracking = isTracking,
        isSubsumed = isSubsumed,
        containsCenterHit = containsCenter,
    )
}
