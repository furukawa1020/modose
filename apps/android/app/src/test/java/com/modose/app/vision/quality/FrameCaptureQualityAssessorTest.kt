package com.modose.app.vision.quality

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuCameraImagePlane
import com.modose.app.ar.image.CpuImageAcquisitionResult
import com.modose.app.ar.image.CpuImageRuntimeSkipReason
import com.modose.app.ar.plane.HorizontalPlaneState
import com.modose.app.ar.plane.SelectedHorizontalPlane
import com.modose.app.ar.session.ArCameraFrame
import com.modose.app.ar.session.ArTrackingDiagnostics
import com.modose.app.ar.session.ArTrackingIssue
import com.modose.app.ar.session.ArTrackingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCaptureQualityAssessorTest {
    private val assessor = FrameCaptureQualityAssessor()

    @Test
    fun `allows a stable textured frame with tracking and a plane`() {
        val result = assessor.assess(
            frame = frame(),
            angularVelocityRadPerSecond = 0.1,
            roiCoverage = 0.70,
        )

        val quality = (result as FrameCaptureQualityResult.Evaluated).quality
        assertTrue(quality.captureAllowed)
        assertTrue(quality.reasonCodes.isEmpty())
    }

    @Test
    fun `combines tracking plane motion and roi failures`() {
        val result = assessor.assess(
            frame = frame(
                tracking = ArTrackingDiagnostics(
                    phase = ArTrackingPhase.Paused,
                    issue = ArTrackingIssue.ExcessiveMotion,
                ),
                planeState = HorizontalPlaneState.Searching,
            ),
            angularVelocityRadPerSecond = 2.0,
            roiCoverage = 0.10,
        )

        val quality = (result as FrameCaptureQualityResult.Evaluated).quality
        assertFalse(quality.captureAllowed)
        assertTrue(CaptureQualityReason.TrackingUnavailable in quality.reasonCodes)
        assertTrue(CaptureQualityReason.PlaneNotFound in quality.reasonCodes)
        assertTrue(CaptureQualityReason.ExcessiveMotion in quality.reasonCodes)
        assertTrue(CaptureQualityReason.RoiInsufficient in quality.reasonCodes)
    }

    @Test
    fun `does not reuse quality when cpu image is unavailable`() {
        val result = assessor.assess(
            frame = frame().copy(
                cpuImageResult = CpuImageAcquisitionResult.Skipped(
                    CpuImageRuntimeSkipReason.NotAvailable,
                ),
            ),
            angularVelocityRadPerSecond = 0.0,
            roiCoverage = 0.80,
        )

        assertEquals(
            FrameCaptureQualityUnavailableReason.CpuImageUnavailable,
            (result as FrameCaptureQualityResult.Unavailable).reason,
        )
    }

    @Test
    fun `rejects invalid angular velocity before evaluating image`() {
        val result = assessor.assess(
            frame = frame(),
            angularVelocityRadPerSecond = Double.NaN,
            roiCoverage = 0.80,
        )

        assertEquals(
            FrameCaptureQualityUnavailableReason.InvalidAngularVelocity,
            (result as FrameCaptureQualityResult.Unavailable).reason,
        )
    }

    private fun frame(
        tracking: ArTrackingDiagnostics = ArTrackingDiagnostics(
            phase = ArTrackingPhase.Tracking,
            issue = ArTrackingIssue.None,
        ),
        planeState: HorizontalPlaneState = HorizontalPlaneState.Tracking(
            SelectedHorizontalPlane(
                id = 1L,
                distanceMeters = 0.5f,
                extentXMeters = 1.0f,
                extentZMeters = 0.7f,
            ),
        ),
    ) = ArCameraFrame(
        timestampNanos = 1L,
        transformedTextureCoordinates = null,
        trackingDiagnostics = tracking,
        horizontalPlaneState = planeState,
        cpuImageResult = CpuImageAcquisitionResult.Acquired(texturedImage()),
    )

    private fun texturedImage(): CpuCameraImage {
        val width = 4
        val height = 4
        val luma = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if ((x + y) % 2 == 0) 80.toByte() else 180.toByte()
        }
        val chroma = CpuCameraImagePlane(
            bytes = byteArrayOf(128.toByte()),
            rowStride = 1,
            pixelStride = 1,
        )
        return CpuCameraImage(
            widthPx = width,
            heightPx = height,
            timestampNanos = 1L,
            planes = listOf(
                CpuCameraImagePlane(luma, width, 1),
                chroma,
                chroma,
            ),
        )
    }
}
