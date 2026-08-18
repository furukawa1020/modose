package com.modose.app.ar.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Yuv420ToNv21ConverterTest {
    @Test
    fun `converts padded and pixel strided yuv planes to compact nv21`() {
        val source = CpuCameraImage(
            widthPx = 4,
            heightPx = 2,
            timestampNanos = 1_000L,
            planes = listOf(
                plane(
                    1, 2, 3, 4, 99, 99,
                    5, 6, 7, 8, 99, 99,
                    rowStride = 6,
                    pixelStride = 1,
                ),
                plane(10, 99, 11, 99, rowStride = 4, pixelStride = 2),
                plane(20, 99, 21, 99, rowStride = 4, pixelStride = 2),
            ),
        )

        val result = Yuv420ToNv21Converter.convert(source) as Nv21ConversionResult.Converted

        assertTrue(
            result.image.bytes.contentEquals(
                byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11),
            ),
        )
    }

    @Test
    fun `rejects missing plane data and odd dimensions`() {
        val valid = image()
        val shortY = valid.copy(
            planes = valid.planes.toMutableList().also { planes ->
                planes[0] = planes[0].copy(bytes = byteArrayOf(1))
            },
        )

        assertRejected(shortY)
        assertRejected(valid.copy(widthPx = 3))
    }

    @Test
    fun `does not mutate source plane bytes`() {
        val source = image()
        val before = source.planes.map { plane -> plane.bytes.copyOf() }

        Yuv420ToNv21Converter.convert(source)

        source.planes.zip(before).forEach { (plane, original) ->
            assertTrue(plane.bytes.contentEquals(original))
        }
    }

    private fun image() = CpuCameraImage(
        widthPx = 2,
        heightPx = 2,
        timestampNanos = 1_000L,
        planes = listOf(
            plane(1, 2, 3, 4, rowStride = 2, pixelStride = 1),
            plane(10, rowStride = 1, pixelStride = 1),
            plane(20, rowStride = 1, pixelStride = 1),
        ),
    )

    private fun plane(
        vararg values: Int,
        rowStride: Int,
        pixelStride: Int,
    ) = CpuCameraImagePlane(
        bytes = ByteArray(values.size) { index -> values[index].toByte() },
        rowStride = rowStride,
        pixelStride = pixelStride,
    )

    private fun assertRejected(source: CpuCameraImage) {
        assertEquals(
            VlmImageFailureReason.InvalidYuv,
            (Yuv420ToNv21Converter.convert(source) as Nv21ConversionResult.Rejected).reason,
        )
    }
}
