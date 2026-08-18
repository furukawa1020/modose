package com.modose.app.ar.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuCameraImageCopierTest {
    @Test
    fun `copies all planes and closes the source`() {
        val source = source()

        val result = CpuCameraImageCopier().copy(source)

        assertTrue(result is CpuImageAcquisitionResult.Acquired)
        val image = (result as CpuImageAcquisitionResult.Acquired).image
        assertTrue(image.planes[1].bytes.contentEquals(byteArrayOf(2, 3)))
        assertEquals(2, image.planes[1].pixelStride)
        assertEquals(1, source.closeCount)
    }

    @Test
    fun `closes the source when image validation fails`() {
        val source = source(width = 0)

        assertEquals(
            CpuImageAcquisitionResult.Failed(CpuImageFailureReason.InvalidImage),
            CpuCameraImageCopier().copy(source),
        )
        assertEquals(1, source.closeCount)
    }

    @Test
    fun `closes the source when a plane copy throws`() {
        val source = source(throwOnCopy = true)

        assertEquals(
            CpuImageAcquisitionResult.Failed(CpuImageFailureReason.AcquisitionFailed),
            CpuCameraImageCopier().copy(source),
        )
        assertEquals(1, source.closeCount)
    }

    private fun source(
        width: Int = 640,
        throwOnCopy: Boolean = false,
    ) = FakeImageSource(
        widthPx = width,
        planes = listOf(
            FakePlane(byteArrayOf(1), 640, 1),
            FakePlane(byteArrayOf(2, 3), 320, 2, throwOnCopy),
            FakePlane(byteArrayOf(4, 5), 320, 2),
        ),
    )

    private class FakeImageSource(
        override val widthPx: Int,
        override val planes: List<CpuImagePlaneSource>,
    ) : CloseableCpuImageSource {
        override val heightPx = 480
        override val timestampNanos = 1_000L
        var closeCount = 0

        override fun close() {
            closeCount += 1
        }
    }

    private class FakePlane(
        private val bytes: ByteArray,
        override val rowStride: Int,
        override val pixelStride: Int,
        private val throwOnCopy: Boolean = false,
    ) : CpuImagePlaneSource {
        override fun copyRemainingBytes(): ByteArray {
            if (throwOnCopy) error("copy failed")
            return bytes.copyOf()
        }
    }
}
