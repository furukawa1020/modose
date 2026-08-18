package com.modose.app.ar.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuCameraImageContractTest {
    @Test
    fun `requests at most once per minimum interval`() {
        val policy = CpuImageAcquisitionPolicy(minimumIntervalNanos = 100L)

        assertEquals(CpuImageAcquisitionDecision.Acquire, policy.decide(1_000L, true))
        assertEquals(
            CpuImageAcquisitionDecision.Skip(CpuImageSkipReason.NotDue),
            policy.decide(1_099L, true),
        )
        assertEquals(CpuImageAcquisitionDecision.Acquire, policy.decide(1_100L, true))
    }

    @Test
    fun `rejects tracking loss and duplicate delivered timestamps`() {
        val policy = CpuImageAcquisitionPolicy(minimumIntervalNanos = 100L)

        assertEquals(
            CpuImageAcquisitionDecision.Skip(CpuImageSkipReason.TrackingUnavailable),
            policy.decide(1_000L, false),
        )
        assertEquals(CpuImageAcquisitionDecision.Acquire, policy.decide(1_000L, true))
        policy.markDelivered(1_000L)
        assertEquals(
            CpuImageAcquisitionDecision.Skip(CpuImageSkipReason.DuplicateTimestamp),
            policy.decide(1_000L, true),
        )
    }

    @Test
    fun `validates yuv dimensions planes bytes and strides`() {
        val valid = image()

        assertTrue(CpuCameraImageValidator.isValid(valid))
        assertFalse(CpuCameraImageValidator.isValid(valid.copy(widthPx = 0)))
        assertFalse(CpuCameraImageValidator.isValid(valid.copy(planes = valid.planes.take(2))))
        assertFalse(
            CpuCameraImageValidator.isValid(
                valid.copy(planes = valid.planes.toMutableList().also { planes ->
                    planes[1] = planes[1].copy(rowStride = 0)
                }),
            ),
        )
    }

    private fun image() = CpuCameraImage(
        widthPx = 640,
        heightPx = 480,
        timestampNanos = 1_000L,
        planes = listOf(
            CpuCameraImagePlane(byteArrayOf(1), rowStride = 640, pixelStride = 1),
            CpuCameraImagePlane(byteArrayOf(2), rowStride = 320, pixelStride = 2),
            CpuCameraImagePlane(byteArrayOf(3), rowStride = 320, pixelStride = 2),
        ),
    )
}
