package com.modose.app.ar.image

data class CpuCameraImagePlane(
    val bytes: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is CpuCameraImagePlane &&
            bytes.contentEquals(other.bytes) &&
            rowStride == other.rowStride &&
            pixelStride == other.pixelStride

    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + rowStride) + pixelStride
}

data class CpuCameraImage(
    val widthPx: Int,
    val heightPx: Int,
    val timestampNanos: Long,
    val planes: List<CpuCameraImagePlane>,
)

sealed interface CpuImageAcquisitionDecision {
    data object Acquire : CpuImageAcquisitionDecision
    data class Skip(val reason: CpuImageSkipReason) : CpuImageAcquisitionDecision
}

enum class CpuImageSkipReason {
    NotDue,
    DuplicateTimestamp,
    TrackingUnavailable,
    InvalidTimestamp,
}

sealed interface CpuImageAcquisitionResult {
    data class Acquired(val image: CpuCameraImage) : CpuImageAcquisitionResult
    data class Skipped(val reason: CpuImageRuntimeSkipReason) : CpuImageAcquisitionResult
    data class Failed(val reason: CpuImageFailureReason) : CpuImageAcquisitionResult
}

enum class CpuImageRuntimeSkipReason {
    NotRequested,
    NotAvailable,
    TrackingUnavailable,
}

enum class CpuImageFailureReason {
    DeadlineExceeded,
    InvalidImage,
    AcquisitionFailed,
}

class CpuImageAcquisitionPolicy(
    private val minimumIntervalNanos: Long = 100_000_000L,
) {
    init {
        require(minimumIntervalNanos > 0L)
    }

    private var lastRequestTimestampNanos: Long? = null
    private var lastDeliveredTimestampNanos: Long? = null

    fun decide(
        frameTimestampNanos: Long,
        isTracking: Boolean,
    ): CpuImageAcquisitionDecision {
        if (!isTracking) {
            return CpuImageAcquisitionDecision.Skip(CpuImageSkipReason.TrackingUnavailable)
        }
        if (frameTimestampNanos <= 0L) {
            return CpuImageAcquisitionDecision.Skip(CpuImageSkipReason.InvalidTimestamp)
        }
        if (frameTimestampNanos == lastDeliveredTimestampNanos) {
            return CpuImageAcquisitionDecision.Skip(CpuImageSkipReason.DuplicateTimestamp)
        }

        val lastRequest = lastRequestTimestampNanos
        if (lastRequest != null && frameTimestampNanos - lastRequest < minimumIntervalNanos) {
            return CpuImageAcquisitionDecision.Skip(CpuImageSkipReason.NotDue)
        }
        lastRequestTimestampNanos = frameTimestampNanos
        return CpuImageAcquisitionDecision.Acquire
    }

    fun markDelivered(imageTimestampNanos: Long) {
        require(imageTimestampNanos > 0L)
        lastDeliveredTimestampNanos = imageTimestampNanos
    }

    fun reset() {
        lastRequestTimestampNanos = null
        lastDeliveredTimestampNanos = null
    }
}

object CpuCameraImageValidator {
    fun isValid(image: CpuCameraImage): Boolean =
        image.widthPx > 0 &&
            image.heightPx > 0 &&
            image.timestampNanos > 0L &&
            image.planes.size == YUV_PLANE_COUNT &&
            image.planes.all { plane ->
                plane.bytes.isNotEmpty() && plane.rowStride > 0 && plane.pixelStride > 0
            }

    private const val YUV_PLANE_COUNT = 3
}
