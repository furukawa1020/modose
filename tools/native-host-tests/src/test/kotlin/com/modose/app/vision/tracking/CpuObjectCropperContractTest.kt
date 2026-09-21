package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuCameraImagePlane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CpuObjectCropperContractTest {
    @Test
    fun neutralChromaConvertsLimitedRangeBlackAndWhite() {
        val image = image(2, 2, y = intArrayOf(16, 235, 235, 16))
        assertArrayEquals(intArrayOf(BLACK, WHITE, WHITE, BLACK), cropped(image).pixels)
    }

    @Test
    fun knownYuvPrimariesConvertToRedAndBlue() {
        assertArrayEquals(IntArray(4) { RED }, cropped(image(2, 2,
            y = IntArray(4) { 81 }, u = intArrayOf(90), v = intArrayOf(240))).pixels)
        assertArrayEquals(IntArray(4) { BLUE }, cropped(image(2, 2,
            y = IntArray(4) { 41 }, u = intArrayOf(112 + 128), v = intArrayOf(110))).pixels)
    }

    @Test
    fun rowPaddingIsNotTreatedAsPixels() {
        val plain = image(4, 4, y = IntArray(16) { if (it % 2 == 0) 16 else 235 })
        val padded = plain.copy(planes = listOf(
            plane(4, 4, 7, 1, IntArray(16) { if (it % 2 == 0) 16 else 235 }),
            plane(2, 2, 5, 1, IntArray(4) { 128 }),
            plane(2, 2, 5, 1, IntArray(4) { 128 })))
        assertArrayEquals(cropped(plain).pixels, cropped(padded).pixels)
    }

    @Test
    fun pixelStrideIsRespectedInEveryPlane() {
        val source = image(4, 4)
        val strided = source.copy(planes = listOf(
            plane(4, 4, 8, 2, IntArray(16) { 16 }),
            plane(2, 2, 4, 2, IntArray(4) { 128 }),
            plane(2, 2, 4, 2, IntArray(4) { 128 })))
        assertArrayEquals(cropped(source).pixels, cropped(strided).pixels)
    }

    @Test
    fun oddImageDimensionsUseCeilingChromaDimensions() {
        val crop = cropped(image(3, 3), DetectedImageObject(null, 1, 1, 3, 3))
        assertEquals(2, crop.width)
        assertEquals(2, crop.height)
        assertArrayEquals(IntArray(4) { BLACK }, crop.pixels)
    }

    @Test
    fun oddCropOriginUsesOriginalChromaCoordinates() {
        val source = image(4, 4, y = IntArray(16) { 81 },
            u = intArrayOf(90, 128, 128, 128), v = intArrayOf(240, 128, 128, 128))
        val crop = cropped(source, DetectedImageObject(0, 1, 1, 3, 3))
        assertArrayEquals(intArrayOf(RED, 0xff4c4c4c.toInt(), 0xff4c4c4c.toInt(), 0xff4c4c4c.toInt()), crop.pixels)
    }

    @Test
    fun outOfBoundsEmptyAndReversedBoxesAreRejectedWithoutClamping() {
        for (box in listOf(DetectedImageObject(0, -1, 0, 2, 2),
            DetectedImageObject(0, 0, -1, 2, 2), DetectedImageObject(0, 0, 0, 3, 2),
            DetectedImageObject(0, 0, 0, 2, 3), DetectedImageObject(0, 1, 0, 1, 2),
            DetectedImageObject(0, 2, 0, 1, 2), DetectedImageObject(0, 0, 1, 2, 1))) {
            assertEquals(ObjectCropResult.Rejected(ObjectCropFailure.INVALID_BOX), CpuObjectCropper.crop(image(2, 2), box))
        }
    }

    @Test
    fun invalidDimensionsTimestampAndPixelBudgetAreRejected() {
        val valid = image(2, 2)
        for (invalid in listOf(valid.copy(widthPx = 0), valid.copy(heightPx = -1),
            valid.copy(timestampNanos = 0), valid.copy(widthPx = 1921, heightPx = 1080))) {
            assertEquals(ObjectCropResult.Rejected(ObjectCropFailure.INVALID_IMAGE),
                CpuObjectCropper.crop(invalid, DetectedImageObject(0, 0, 0, 1, 1)))
        }
    }

    @Test
    fun missingPlanesAreRejected() {
        val source = image(2, 2)
        assertPlaneFailure(source.copy(planes = source.planes.take(2)))
    }

    @Test
    fun truncatedLastRowIsRejectedButItsPaddingIsNotRequired() {
        val source = image(4, 4)
        // plane() allocates only through the final sample, without last-row padding.
        val exact = source.copy(planes = listOf(
            plane(4, 4, 8, 1, IntArray(16) { 16 }), source.planes[1], source.planes[2]))
        assertArrayEquals(IntArray(16) { BLACK }, cropped(exact).pixels)
        val y = exact.planes[0]
        assertPlaneFailure(exact.copy(planes = listOf(
            y.copy(bytes = y.bytes.copyOf(y.bytes.size - 1)), exact.planes[1], exact.planes[2])))
    }

    @Test
    fun nonPositiveAndOverlappingStridesAreRejected() {
        val source = image(2, 2)
        for (plane in listOf(source.planes[0].copy(rowStride = 0),
            source.planes[0].copy(pixelStride = -1), source.planes[0].copy(rowStride = 1))) {
            assertPlaneFailure(source.copy(planes = listOf(plane, source.planes[1], source.planes[2])))
        }
    }

    @Test
    funHugeStridesCannotWrapIntoTheByteArray() {
        val source = image(2, 2)
        val huge = source.planes[0].copy(rowStride = Int.MAX_VALUE, pixelStride = Int.MAX_VALUE)
        assertPlaneFailure(source.copy(planes = listOf(huge, source.planes[1], source.planes[2])))
    }

    @Test
    fun outputDoesNotAliasInputOrFutureCrops() {
        val source = image(2, 2)
        val before = source.planes[0].bytes.copyOf()
        val first = cropped(source)
        first.pixels.fill(WHITE)
        assertArrayEquals(before, source.planes[0].bytes)
        assertArrayEquals(IntArray(4) { BLACK }, cropped(source).pixels)
    }

    @Test
    fun preprocessingIdentityDeclaresTheFixedColorConvention() {
        assertEquals("cpu-yuv420-bt601-limited-rgb-crop-v1", CpuObjectCropper.PREPROCESSING_ID)
    }

    private fun assertPlaneFailure(image: CpuCameraImage) {
        assertEquals(ObjectCropResult.Rejected(ObjectCropFailure.INVALID_PLANES),
            CpuObjectCropper.crop(image, DetectedImageObject(0, 0, 0, 1, 1)))
    }

    private fun cropped(image: CpuCameraImage,
        box: DetectedImageObject = DetectedImageObject(0, 0, 0, image.widthPx, image.heightPx)) =
        (CpuObjectCropper.crop(image, box) as ObjectCropResult.Cropped).crop

    private fun image(width: Int, height: Int,
        y: IntArray = IntArray(width * height) { 16 },
        u: IntArray = IntArray(((width + 1) / 2) * ((height + 1) / 2)) { 128 },
        v: IntArray = IntArray(((width + 1) / 2) * ((height + 1) / 2)) { 128 }): CpuCameraImage {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        return CpuCameraImage(width, height, 1, listOf(
            plane(width, height, width, 1, y), plane(cw, ch, cw, 1, u), plane(cw, ch, cw, 1, v)))
    }

    private fun plane(width: Int, height: Int, row: Int, pixel: Int, values: IntArray): CpuCameraImagePlane {
        val bytes = ByteArray((height - 1) * row + (width - 1) * pixel + 1) { 127 }
        for (y in 0 until height) for (x in 0 until width) bytes[y * row + x * pixel] = values[y * width + x].toByte()
        return CpuCameraImagePlane(bytes, row, pixel)
    }

    companion object {
        private val BLACK = 0xff000000.toInt()
        private val WHITE = 0xffffffff.toInt()
        private val RED = 0xffff0000.toInt()
        private val BLUE = 0xff0000ff.toInt()
    }
}
