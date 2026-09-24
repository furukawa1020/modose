package com.modose.app.core

import org.junit.Assert.*
import org.junit.Test

class NativeComparisonProjectorContractTest {
    private fun geometry(radius: Double = 1.0) = doubleArrayOf(
        0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
        -radius, -radius, radius, -radius, radius, radius, -radius, radius,
    )
    private fun baseline(radius: Double = 1.0) = NativeBaselineTargets.project(geometry(radius), listOf(
        CoreDetection(7, CoreVector(0.0, 2.0, 0.0), CoreVector(0.0, -1.0, 0.0), false)))
    private fun inverse() = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
        0.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0,
    )
    private fun capture(matrix: DoubleArray = inverse(), scene: String = "scene") =
        NativeImageCapture.create(NativeGuidanceEpoch(scene), 0, 1234, 100, 100, 100, 100,
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), matrix)!!
    private fun box(id: Int = 10, savedId: Int = 7, x: Double = 60.0) =
        NativeComparedBox(savedId, NativeImageBox(id, x - 5, 45.0, x + 5, 55.0, false))
    private fun projected(result: NativeComparisonProjection) =
        (result as NativeComparisonProjection.Projected).distances

    @Test fun calculatesMetersFromRealRustProjectionNotCloudDistance() {
        val result = projected(NativeComparisonProjector.project("scene", 1234, capture(), baseline(), listOf(box())))
        assertEquals(10, result.single().currentId)
        assertEquals(7, result.single().savedId)
        assertEquals(0.2, result.single().currentPosition!!.x, 1e-9)
        assertEquals(0.2, result.single().distanceMeters!!, 1e-9)
    }

    @Test fun targetsAreSelectedBySavedIdNotCandidateOrder() {
        val saved = NativeBaselineTargets.project(geometry(), listOf(
            CoreDetection(7, CoreVector(0.0, 2.0, 0.0), CoreVector(0.0, -1.0, 0.0), false),
            CoreDetection(3, CoreVector(-0.5, 2.0, 0.0), CoreVector(0.0, -1.0, 0.0), false)))
        val results = projected(NativeComparisonProjector.project("scene", 1234, capture(), saved,
            listOf(box(20, 3, 25.0), box(10, 7))))
        assertEquals(0.0, results[0].distanceMeters!!, 1e-9)
        assertEquals(0.2, results[1].distanceMeters!!, 1e-9)
    }

    @Test fun outsidePlaneNeverBecomesZeroDistance() {
        val result = projected(NativeComparisonProjector.project("scene", 1234, capture(), baseline(0.1), listOf(box())))
        assertNull(result.single().currentPosition)
        assertNull(result.single().distanceMeters)
    }

    @Test fun parallelAndBehindRaysRemainUnprojectable() {
        val parallel = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
        val behind = inverse().apply { this[13] = -3.0 }
        for (matrix in listOf(parallel, behind)) {
            val result = projected(NativeComparisonProjector.project("scene", 1234, capture(matrix), baseline(), listOf(box())))
            assertNull(result.single().distanceMeters)
        }
    }

    @Test fun sceneAndImageMismatchAreRejected() {
        for ((scene, time) in listOf("other" to 1234L, "scene" to 9999L)) {
            assertEquals(NativeComparisonProjection.Rejected(NativeComparisonRejection.FRAME_MISMATCH),
                NativeComparisonProjector.project(scene, time, capture(), baseline(), listOf(box())))
        }
    }

    @Test fun duplicateOrUnknownIdsAreRejectedBeforeProjection() {
        for (objects in listOf(listOf(box(), box()), listOf(box(savedId = 99)),
            listOf(box(id = 0)), List(6) { box(id = it + 1) })) {
            assertEquals(NativeComparisonProjection.Rejected(NativeComparisonRejection.INVALID_OBJECT_SET),
                NativeComparisonProjector.project("scene", 1234, capture(), baseline(), objects))
        }
    }

    @Test fun invalidImageBoxIsNotTreatedAsMissing() {
        val bad = box().let { it.copy(box = it.box.copy(left = -1.0)) }
        assertEquals(NativeComparisonProjection.Rejected(NativeComparisonRejection.INVALID_RAY),
            NativeComparisonProjector.project("scene", 1234, capture(), baseline(), listOf(bad)))
    }

    @Test fun noPositionedObjectsProducesNoDistances() {
        assertTrue(projected(NativeComparisonProjector.project("scene", 1234, capture(), baseline(), emptyList())).isEmpty())
    }

    @Test fun currentAnchorTranslationDoesNotChangeSavedPlaneDistance() {
        val rebase = NativeAnchorRebase.create(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(10.0, 0.0, -4.0, 0.0, 0.0, 0.0, 1.0))!!
        val current = inverse().apply { this[12] = 10.0; this[14] = -4.0 }
        val corrected = capture(rebase.rebaseInverseViewProjection(current)!!)
        val result = projected(NativeComparisonProjector.project("scene", 1234, corrected, baseline(), listOf(box())))
        assertEquals(0.2, result.single().distanceMeters!!, 1e-9)
    }

    @Test fun resultListCannotBeMutated() {
        val result = projected(NativeComparisonProjector.project("scene", 1234, capture(), baseline(), listOf(box())))
        assertThrows(UnsupportedOperationException::class.java) { (result as MutableList<NativeCandidateDistance>).clear() }
    }
}
