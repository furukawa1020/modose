package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuCameraImagePlane

internal data class ArgbObjectCrop(val width: Int, val height: Int, val pixels: IntArray)
internal enum class ObjectCropFailure { INVALID_IMAGE, INVALID_BOX, INVALID_PLANES }
internal sealed interface ObjectCropResult {
    data class Cropped(val crop: ArgbObjectCrop) : ObjectCropResult
    data class Rejected(val reason: ObjectCropFailure) : ObjectCropResult
}

/** Raw sensor coordinates; no rotation, resizing, or padding. */
internal object CpuObjectCropper {
    const val PREPROCESSING_ID = "cpu-yuv420-bt601-limited-rgb-crop-v1"

    fun crop(image: CpuCameraImage, box: DetectedImageObject): ObjectCropResult {
        if (image.timestampNanos <= 0 || image.widthPx <= 0 || image.heightPx <= 0 ||
            image.widthPx.toLong() * image.heightPx > 1920L * 1080L
        ) return reject(ObjectCropFailure.INVALID_IMAGE)
        if (box.left < 0 || box.top < 0 || box.right > image.widthPx || box.bottom > image.heightPx ||
            box.left >= box.right || box.top >= box.bottom
        ) return reject(ObjectCropFailure.INVALID_BOX)
        if (image.planes.size != 3) return reject(ObjectCropFailure.INVALID_PLANES)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val chromaWidth = (image.widthPx + 1) / 2
        val chromaHeight = (image.heightPx + 1) / 2
        if (!validPlane(yPlane, image.widthPx, image.heightPx) ||
            !validPlane(uPlane, chromaWidth, chromaHeight) ||
            !validPlane(vPlane, chromaWidth, chromaHeight)
        ) return reject(ObjectCropFailure.INVALID_PLANES)
        val width = box.right - box.left
        val height = box.bottom - box.top
        val pixels = IntArray(width * height)
        var destination = 0
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                val luminance = (sample(yPlane, x, y) - 16).coerceAtLeast(0)
                val u = sample(uPlane, x / 2, y / 2) - 128
                val v = sample(vPlane, x / 2, y / 2) - 128
                val red = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
                pixels[destination++] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return ObjectCropResult.Cropped(ArgbObjectCrop(width, height, pixels))
    }

    private fun validPlane(plane: CpuCameraImagePlane, width: Int, height: Int): Boolean {
        if (plane.rowStride <= 0 || plane.pixelStride <= 0) return false
        val rowBytes = (width - 1L) * plane.pixelStride + 1
        val required = (height - 1L) * plane.rowStride + rowBytes
        return rowBytes <= plane.rowStride && required <= plane.bytes.size
    }

    private fun sample(plane: CpuCameraImagePlane, x: Int, y: Int): Int =
        plane.bytes[(y.toLong() * plane.rowStride + x.toLong() * plane.pixelStride).toInt()].toInt() and 255

    private fun reject(reason: ObjectCropFailure) = ObjectCropResult.Rejected(reason)
}
