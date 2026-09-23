package com.modose.app.ar.plane

import org.junit.Assert.*
import org.junit.Test

class CapturedTableGeometryTest {
    private fun geometry() = doubleArrayOf(
        1.0, 2.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0)

    @Test fun matchingFrameReturnsExactGeometry() {
        val snapshot = requireNotNull(CapturedTableGeometry.create(10, 7, geometry()))
        assertArrayEquals(geometry(), snapshot.copyFor(10, 7), 0.0)
    }

    @Test fun foreignFrameOrAnchorIsRejected() {
        val snapshot = requireNotNull(CapturedTableGeometry.create(10, 7, geometry()))
        assertNull(snapshot.copyFor(11, 7))
        assertNull(snapshot.copyFor(10, 8))
    }

    @Test fun inputAndOutputArraysAreNotBorrowed() {
        val input = geometry()
        val snapshot = requireNotNull(CapturedTableGeometry.create(10, 7, input))
        input.fill(0.0)
        requireNotNull(snapshot.copyFor(10, 7)).fill(0.0)
        assertArrayEquals(geometry(), snapshot.copyFor(10, 7), 0.0)
    }

    @Test fun invalidClockAndVertexCountsAreRejected() {
        assertNull(CapturedTableGeometry.create(0, 7, geometry()))
        for (size in listOf(0, 14, 16, 138, 139)) {
            assertNull(CapturedTableGeometry.create(10, 7, DoubleArray(size)))
        }
    }

    @Test fun nonFiniteGeometryIsRejected() {
        for (index in geometry().indices) {
            val input = geometry()
            input[index] = Double.NaN
            assertNull(CapturedTableGeometry.create(10, 7, input))
        }
    }

    @Test fun invalidOrNonHorizontalBasisIsRejected() {
        val scaled = geometry().also { it[3] = 2.0 }
        val parallel = geometry().also { it[6] = 1.0; it[8] = 0.0 }
        val vertical = geometry().also { it[7] = 1.0; it[8] = 0.0 }
        for (input in listOf(scaled, parallel, vertical)) {
            assertNull(CapturedTableGeometry.create(10, 7, input))
        }
    }

    @Test fun repeatedAndDegenerateVerticesAreRejected() {
        val repeated = geometry().also { it[11] = it[9]; it[12] = it[10] }
        val line = geometry().also { for (i in 10 until it.size step 2) it[i] = 0.0 }
        assertNull(CapturedTableGeometry.create(10, 7, repeated))
        assertNull(CapturedTableGeometry.create(10, 7, line))
    }

    @Test fun concaveAndCrossingBoundariesAreRejected() {
        val basis = geometry().copyOfRange(0, 9)
        val concave = basis + doubleArrayOf(-1.0, -1.0, 1.0, -1.0, 0.0, 0.0, 1.0, 1.0, -1.0, 1.0)
        val crossing = basis + doubleArrayOf(-1.0, -1.0, 1.0, 1.0, 1.0, -1.0, -1.0, 1.0)
        assertNull(CapturedTableGeometry.create(10, 7, concave))
        assertNull(CapturedTableGeometry.create(10, 7, crossing))
    }

    @Test fun clockwiseBoundaryIsAcceptedWithoutReordering() {
        val input = geometry().copyOfRange(0, 9) +
            doubleArrayOf(-1.0, 1.0, 1.0, 1.0, 1.0, -1.0, -1.0, -1.0)
        val snapshot = requireNotNull(CapturedTableGeometry.create(10, 7, input))
        assertArrayEquals(input, snapshot.copyFor(10, 7), 0.0)
    }
}
