package com.modose.app.ar.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

data class VlmJpegImage(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val mimeType: String = MIME_TYPE,
) {
    override fun equals(other: Any?): Boolean =
        other is VlmJpegImage &&
            bytes.contentEquals(other.bytes) &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            mimeType == other.mimeType

    override fun hashCode(): Int =
        31 * (31 * (31 * bytes.contentHashCode() + widthPx) + heightPx) + mimeType.hashCode()

    companion object {
        const val MIME_TYPE = "image/jpeg"
    }
}

sealed interface VlmJpegEncodingResult {
    data class Encoded(val image: VlmJpegImage) : VlmJpegEncodingResult
    data class Rejected(val reason: VlmImageFailureReason) : VlmJpegEncodingResult
}

class VlmJpegEncoder {
    fun encode(
        source: CpuCameraImage,
        plan: VlmImageEncodingPlan,
    ): VlmJpegEncodingResult {
        val nv21 = when (val conversion = Yuv420ToNv21Converter.convert(source)) {
            is Nv21ConversionResult.Converted -> conversion.image
            is Nv21ConversionResult.Rejected -> return rejected(conversion.reason)
        }

        var decoded: Bitmap? = null
        var rotated: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val croppedJpeg = ByteArrayOutputStream().use { output ->
                val cropSucceeded = YuvImage(
                    nv21.bytes,
                    ImageFormat.NV21,
                    nv21.widthPx,
                    nv21.heightPx,
                    null,
                ).compressToJpeg(
                    Rect(
                        plan.roi.left,
                        plan.roi.top,
                        plan.roi.rightExclusive,
                        plan.roi.bottomExclusive,
                    ),
                    INTERMEDIATE_JPEG_QUALITY,
                    output,
                )
                if (!cropSucceeded) return rejected(VlmImageFailureReason.EncodeFailed)
                output.toByteArray()
            }
            val decodedBitmap = BitmapFactory.decodeByteArray(croppedJpeg, 0, croppedJpeg.size)
                ?: return rejected(VlmImageFailureReason.EncodeFailed)
            decoded = decodedBitmap
            val rotatedBitmap = Bitmap.createBitmap(
                decodedBitmap,
                0,
                0,
                decodedBitmap.width,
                decodedBitmap.height,
                Matrix().apply { postRotate(plan.rotation.degreesClockwise.toFloat()) },
                true,
            )
            rotated = rotatedBitmap
            val scaledBitmap = Bitmap.createScaledBitmap(
                rotatedBitmap,
                plan.outputWidthPx,
                plan.outputHeightPx,
                true,
            )
            scaled = scaledBitmap
            val finalBytes = ByteArrayOutputStream().use { output ->
                val encoded = scaledBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    plan.jpegQuality,
                    output,
                )
                if (!encoded) return rejected(VlmImageFailureReason.EncodeFailed)
                output.toByteArray()
            }
            val sizeFailure = VlmImageEncodingPlanner.validateEncodedSize(finalBytes.size)
            if (sizeFailure != null) {
                rejected(sizeFailure)
            } else {
                VlmJpegEncodingResult.Encoded(
                    VlmJpegImage(
                        bytes = finalBytes,
                        widthPx = plan.outputWidthPx,
                        heightPx = plan.outputHeightPx,
                    ),
                )
            }
        } catch (_: RuntimeException) {
            rejected(VlmImageFailureReason.EncodeFailed)
        } finally {
            recycleDistinct(scaled, rotated, decoded)
        }
    }

    private fun rejected(reason: VlmImageFailureReason) = VlmJpegEncodingResult.Rejected(reason)

    private fun recycleDistinct(vararg bitmaps: Bitmap?) {
        val recycledIds = mutableSetOf<Int>()
        bitmaps.filterNotNull().forEach { bitmap ->
            if (recycledIds.add(System.identityHashCode(bitmap)) && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private companion object {
        const val INTERMEDIATE_JPEG_QUALITY = 100
    }
}
