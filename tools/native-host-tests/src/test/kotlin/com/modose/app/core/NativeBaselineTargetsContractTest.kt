package com.modose.app.core

import org.junit.Assert.*
import org.junit.Test

class NativeBaselineTargetsContractTest {
    private fun geometry() = doubleArrayOf(
        0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0,
    )
    private fun ray(id: Int = 7, x: Double = 0.2, z: Double = -0.3) =
        CoreDetection(id, CoreVector(x, 2.0, z), CoreVector(0.0, -1.0, 0.0), false)
    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Invalid projection was accepted")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun preservesObjectOrderAndMeters() {
        val result = NativeBaselineTargets.project(geometry(), listOf(ray(7), ray(2, -0.4, 0.5)))
        assertEquals(2, result.size)
        assertArrayEquals(intArrayOf(7, 2), result.copyIds())
        assertArrayEquals(doubleArrayOf(0.2, -0.3, -0.4, 0.5), result.copyPositions(), 1e-9)
        NativeSceneSession.create(result.copyGeometry(), result.copyIds(), result.copyPositions()).use {
            val guidance = it.guidance(0) as NativeGuidance.Recover
            assertEquals(NativeRecoveryReason.UNOBSERVED, guidance.reason)
            assertTrue(guidance.objectId in result.copyIds())
        }
    }

    @Test fun targetsAndGeometryDoNotExposeMutableStorage() {
        val input = geometry()
        val result = NativeBaselineTargets.project(input, listOf(ray()))
        input.fill(Double.NaN)
        result.copyGeometry().fill(Double.NaN)
        result.copyIds().fill(0)
        result.copyPositions().fill(Double.NaN)
        assertArrayEquals(geometry(), result.copyGeometry(), 0.0)
        assertArrayEquals(intArrayOf(7), result.copyIds())
        assertArrayEquals(doubleArrayOf(0.2, -0.3), result.copyPositions(), 1e-9)
    }

    @Test fun oneOutsideRayRejectsTheEntireBatch() {
        rejected { NativeBaselineTargets.project(geometry(), listOf(ray(), ray(8, 2.0))) }
        assertEquals(1, NativeBaselineTargets.project(geometry(), listOf(ray())).size)
    }

    @Test fun parallelBehindAndZeroRaysAreRejected() {
        for (direction in listOf(CoreVector(1.0, 0.0, 0.0), CoreVector(0.0, 1.0, 0.0),
            CoreVector(0.0, 0.0, 0.0))) {
            rejected { NativeBaselineTargets.project(geometry(), listOf(ray().copy(direction = direction))) }
        }
    }

    @Test fun boundedObjectSetRequiresUniquePositiveIds() {
        for (objects in listOf(emptyList(), List(6) { ray(it + 1) }, listOf(ray(), ray()),
            listOf(ray(0)), listOf(ray(-1)))) {
            rejected { NativeBaselineTargets.project(geometry(), objects) }
        }
        assertEquals(5, NativeBaselineTargets.project(geometry(), List(5) { ray(it + 1) }).size)
    }

    @Test fun nativeBoundaryRejectsMalformedAndNonFiniteArrays() {
        for (rays in listOf(doubleArrayOf(), DoubleArray(7), DoubleArray(36),
            doubleArrayOf(0.0, 2.0, 0.0, Double.NaN, -1.0, 0.0))) {
            rejected { NativeSceneBindings.nativeProjectTargets(geometry(), rays) }
        }
        for (table in listOf(DoubleArray(14), DoubleArray(16), geometry().apply { this[0] = Double.NaN },
            geometry().apply { this[3] = 2.0 })) {
            rejected { NativeSceneBindings.nativeProjectTargets(table, doubleArrayOf(0.0, 2.0, 0.0, 0.0, -1.0, 0.0)) }
        }
    }

    @Test fun translatedRotatedBasisReturnsAnchorRelativeCoordinates() {
        val table = geometry().apply {
            this[0] = 10.0; this[1] = 0.5; this[2] = -4.0
            this[3] = 0.0; this[5] = -1.0; this[6] = 1.0; this[8] = 0.0
        }
        val result = NativeBaselineTargets.project(table, listOf(ray(x = 9.7, z = -4.2)))
        assertArrayEquals(doubleArrayOf(0.2, -0.3), result.copyPositions(), 1e-9)
    }

    @Test fun imageCenterFlowsThroughRealRustProjection() {
        val epoch = NativeGuidanceEpoch("baseline")
        val capture = NativeImageCapture.create(epoch, 0, 1234, 100, 100, 100, 100,
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
                0.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0))!!
        val rays = capture.project(NativeImageRecognition(epoch, 1234,
            listOf(NativeImageBox(3, 55.0, 45.0, 65.0, 55.0, false)), emptyList()))
            as NativeImageProjection.Projected
        val result = NativeBaselineTargets.project(geometry(), rays.detections)
        assertArrayEquals(intArrayOf(3), result.copyIds())
        assertArrayEquals(doubleArrayOf(0.2, 0.0), result.copyPositions(), 1e-9)
    }

    @Test fun actualPolygonRejectsPointsInsideOnlyItsBoundingRectangle() {
        val triangle = geometry().copyOfRange(0, 9) + doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0)
        rejected { NativeBaselineTargets.project(triangle, listOf(ray(x = 0.8, z = 0.8))) }
    }
}
