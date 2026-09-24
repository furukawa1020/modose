package com.modose.app.core

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class NativeAnchorRebaseContractTest {
    private fun pose(x: Double = 0.0, z: Double = 0.0) =
        doubleArrayOf(x, 0.0, z, 0.0, 0.0, 0.0, 1.0)
    private fun identity() = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
    private fun cameraInverse() = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
        0.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0,
    )
    private fun rotatedPose() = doubleArrayOf(10.0, 0.0, -4.0, 0.0, sqrt(0.5), 0.0, sqrt(0.5))
    // Current world is saved world rotated +90 degrees around Y, then translated.
    private fun rotatedCameraInverse() = doubleArrayOf(
        0.0, 0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0,
        0.0, -1.0, 0.0, 0.0, 10.0, 1.0, -4.0, 1.0,
    )

    @Test fun unchangedAnchorPreservesBothMatrixDirections() {
        val transform = NativeAnchorRebase.create(pose(), pose())!!
        assertArrayEquals(cameraInverse(), transform.rebaseInverseViewProjection(cameraInverse())!!, 1e-9)
        assertArrayEquals(identity(), transform.rebaseView(identity())!!, 1e-9)
    }

    @Test fun translationCorrectionKeepsTheSavedWorldRay() {
        val current = cameraInverse().apply { this[12] = 10.0; this[14] = -4.0 }
        val transform = NativeAnchorRebase.create(pose(), pose(10.0, -4.0))!!
        assertArrayEquals(cameraInverse(), transform.rebaseInverseViewProjection(current)!!, 1e-9)
        val view = identity().apply { this[12] = -10.0; this[14] = 4.0 }
        assertArrayEquals(identity(), transform.rebaseView(view)!!, 1e-9)
    }

    @Test fun rotationAndTranslationAreAppliedInTheCorrectOrder() {
        val transform = NativeAnchorRebase.create(pose(), rotatedPose())!!
        assertArrayEquals(cameraInverse(),
            transform.rebaseInverseViewProjection(rotatedCameraInverse())!!, 1e-9)
        // Current camera view is inverse(current anchor pose).
        val currentView = doubleArrayOf(
            0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0,
            -1.0, 0.0, 0.0, 0.0, -4.0, 0.0, -10.0, 1.0,
        )
        assertArrayEquals(identity(), transform.rebaseView(currentView)!!, 1e-9)
    }

    @Test fun savedAnchorNeedNotBeTheWorldOrigin() {
        val transform = NativeAnchorRebase.create(pose(2.0, 3.0), rotatedPose())!!
        val expected = cameraInverse().apply { this[12] = 2.0; this[14] = 3.0 }
        assertArrayEquals(expected,
            transform.rebaseInverseViewProjection(rotatedCameraInverse())!!, 1e-9)
    }

    @Test fun quaternionSignDoesNotChangeTheTransform() {
        val negative = rotatedPose().apply { for (i in 3..6) this[i] = -this[i] }
        val transform = NativeAnchorRebase.create(rotatedPose(), negative)!!
        assertArrayEquals(identity(), transform.rebaseView(identity())!!, 1e-9)
    }

    @Test fun malformedPosesAndMatricesAreRejected() {
        for (bad in listOf(DoubleArray(6), DoubleArray(7), pose().apply { this[6] = 2.0 },
            pose().apply { this[0] = Double.NaN }, pose().apply { this[4] = Double.POSITIVE_INFINITY })) {
            assertNull(NativeAnchorRebase.create(bad, pose()))
            assertNull(NativeAnchorRebase.create(pose(), bad))
        }
        val transform = NativeAnchorRebase.create(pose(), pose())!!
        assertNull(transform.rebaseView(DoubleArray(15)))
        assertNull(transform.rebaseInverseViewProjection(identity().apply { this[0] = Double.NaN }))
    }

    @Test fun callerMutationsCannotChangeAnExistingFrameTransform() {
        val saved = pose()
        val current = rotatedPose()
        val transform = NativeAnchorRebase.create(saved, current)!!
        saved.fill(Double.NaN)
        current.fill(Double.NaN)
        transform.rebaseView(identity())!!.fill(Double.NaN)
        assertArrayEquals(cameraInverse(),
            transform.rebaseInverseViewProjection(rotatedCameraInverse())!!, 1e-9)
    }

    @Test fun rebasedCameraRayProjectsThroughRealRustToTheSameTarget() {
        val transform = NativeAnchorRebase.create(pose(), rotatedPose())!!
        val epoch = NativeGuidanceEpoch("rebase")
        val capture = NativeImageCapture.create(epoch, 0, 1234, 100, 100, 100, 100,
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            transform.rebaseInverseViewProjection(rotatedCameraInverse())!!)!!
        val input = NativeImageRecognition(epoch, 1234,
            listOf(NativeImageBox(1, 55.0, 45.0, 65.0, 55.0, false)), emptyList())
        val detections = (capture.project(input) as NativeImageProjection.Projected).detections
        val table = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
            -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0)
        val result = NativeBaselineTargets.project(table, detections)
        assertArrayEquals(doubleArrayOf(0.2, 0.0), result.copyPositions(), 1e-9)
    }
}
