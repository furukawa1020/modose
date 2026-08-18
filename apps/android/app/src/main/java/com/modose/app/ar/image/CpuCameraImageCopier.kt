package com.modose.app.ar.image

import android.media.Image
import java.nio.ByteBuffer

internal interface CpuImagePlaneSource {
    val rowStride: Int
    val pixelStride: Int

    fun copyRemainingBytes(): ByteArray
}

internal interface CloseableCpuImageSource {
    val widthPx: Int
    val heightPx: Int
    val timestampNanos: Long
    val planes: List<CpuImagePlaneSource>

    fun close()
}

internal class AndroidCpuImageSource(
    private val image: Image,
) : CloseableCpuImageSource {
    override val widthPx: Int
        get() = image.width
    override val heightPx: Int
        get() = image.height
    override val timestampNanos: Long
        get() = image.timestamp
    override val planes: List<CpuImagePlaneSource>
        get() = image.planes.map(::AndroidCpuImagePlaneSource)

    override fun close() = image.close()
}

private class AndroidCpuImagePlaneSource(
    private val plane: Image.Plane,
) : CpuImagePlaneSource {
    override val rowStride: Int
        get() = plane.rowStride
    override val pixelStride: Int
        get() = plane.pixelStride

    override fun copyRemainingBytes(): ByteArray = plane.buffer.copyRemainingBytes()
}

internal class CpuCameraImageCopier {
    fun copy(source: CloseableCpuImageSource): CpuImageAcquisitionResult {
        var result = try {
            val image = CpuCameraImage(
                widthPx = source.widthPx,
                heightPx = source.heightPx,
                timestampNanos = source.timestampNanos,
                planes = source.planes.map { plane ->
                    CpuCameraImagePlane(
                        bytes = plane.copyRemainingBytes(),
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                    )
                },
            )
            if (CpuCameraImageValidator.isValid(image)) {
                CpuImageAcquisitionResult.Acquired(image)
            } else {
                CpuImageAcquisitionResult.Failed(CpuImageFailureReason.InvalidImage)
            }
        } catch (_: RuntimeException) {
            CpuImageAcquisitionResult.Failed(CpuImageFailureReason.AcquisitionFailed)
        }

        try {
            source.close()
        } catch (_: RuntimeException) {
            result = CpuImageAcquisitionResult.Failed(CpuImageFailureReason.AcquisitionFailed)
        }
        return result
    }
}

private fun ByteBuffer.copyRemainingBytes(): ByteArray {
    val readable = duplicate()
    val bytes = ByteArray(readable.remaining())
    readable.get(bytes)
    return bytes
}
