package com.modose.app.flow.compare

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.ar.render.BaselineCaptureValidity
import com.modose.app.network.VisionApiResult
import com.modose.app.network.baseline.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SavedVerificationReservationTest {
    private val validity = BaselineCaptureValidity(1L)
    private val image = VlmJpegImage(byteArrayOf(1, 2, 3), 10, 10)
    private fun saved(attemptCompare: Boolean = true): SavedSceneComparison {
        val owner = SavedSceneComparison("018f0f90-1234-4abc-8def-123456789abc", 100L, validity, image,
            listOf(BaselineObject("cup", "cup", listOf("red"), NormalizedBoundingBox(0, 0, 100, 100),
                false, ObjectSymmetry.Rotational)))
        if (attemptCompare) owner.execute(validity, 101L, image, {}) { VisionApiResult.NetworkFailure }
        return owner
    }

    @Test
    fun reservesThreeFreshUniqueAttemptsAcrossRepeatedCalls() {
        val owner = saved()
        val keys = (102L..104L).map {
            requireNotNull(owner.reserveVerification(validity, it, image)).idempotencyKey
        }
        assertEquals(3, keys.toSet().size)
        assertTrue(keys.all { UUID.fromString(it).version() == 7 })
        assertEquals(3, owner.verificationAttempts)
        assertNull(owner.reserveVerification(validity, 105L, image))
    }

    @Test
    fun invalidOrReusedImagesDoNotConsumeBudget() {
        val owner = saved()
        assertNull(owner.reserveVerification(validity, 100L, image))
        assertNull(owner.reserveVerification(BaselineCaptureValidity(1L), 102L, image))
        assertNull(owner.reserveVerification(validity, 102L, image.copy(bytes = byteArrayOf())))
        assertEquals(0, owner.verificationAttempts)
        assertNotNull(owner.reserveVerification(validity, 102L, image))
        assertNull(owner.reserveVerification(validity, 102L, image))
        assertEquals(1, owner.verificationAttempts)
    }

    @Test
    fun revokedCaptureAndAbsentCompareCannotReserve() {
        assertNull(saved(false).reserveVerification(validity, 102L, image))
        val owner = saved()
        validity.invalidate()
        assertNull(owner.reserveVerification(validity, 102L, image))
    }

    @Test
    fun inputOwnsBothImageCopiesAndExpectedObjectIds() {
        val owner = saved()
        val input = requireNotNull(owner.reserveVerification(validity, 102L, image))
        image.bytes[0] = 99
        assertEquals(1.toByte(), input.baselineImage.bytes[0])
        assertEquals(1.toByte(), input.finalImage.bytes[0])
        assertEquals(setOf("cup"), input.expectedObjectIds)
    }
}
