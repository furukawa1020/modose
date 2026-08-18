package com.modose.app.ar.image

data class Nv21Image(
    val widthPx: Int,
    val heightPx: Int,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Nv21Image &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * widthPx + heightPx) + bytes.contentHashCode()
}

sealed interface Nv21ConversionResult {
    data class Converted(val image: Nv21Image) : Nv21ConversionResult
    data class Rejected(val reason: VlmImageFailureReason) : Nv21ConversionResult
}

object Yuv420ToNv21Converter {
    fun convert(source: CpuCameraImage): Nv21ConversionResult {
        if (
            !CpuCameraImageValidator.isValid(source) ||
            source.widthPx % 2 != 0 ||
            source.heightPx % 2 != 0
        ) {
            return rejected()
        }
        val yPlane = source.planes[0]
        val uPlane = source.planes[1]
        val vPlane = source.planes[2]
        val ySize = source.widthPx * source.heightPx
        val output = ByteArray(ySize + ySize / 2)

        var destination = 0
        for (y in 0 until source.heightPx) {
            for (x in 0 until source.widthPx) {
                val sourceIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                if (sourceIndex !in yPlane.bytes.indices) return rejected()
                output[destination++] = yPlane.bytes[sourceIndex]
            }
        }

        val chromaWidth = source.widthPx / 2
        val chromaHeight = source.heightPx / 2
        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                val uIndex = y * uPlane.rowStride + x * uPlane.pixelStride
                val vIndex = y * vPlane.rowStride + x * vPlane.pixelStride
                if (uIndex !in uPlane.bytes.indices || vIndex !in vPlane.bytes.indices) {
                    return rejected()
                }
                output[destination++] = vPlane.bytes[vIndex]
                output[destination++] = uPlane.bytes[uIndex]
            }
        }
        return Nv21ConversionResult.Converted(
            Nv21Image(source.widthPx, source.heightPx, output),
        )
    }

    private fun rejected() = Nv21ConversionResult.Rejected(VlmImageFailureReason.InvalidYuv)
}
