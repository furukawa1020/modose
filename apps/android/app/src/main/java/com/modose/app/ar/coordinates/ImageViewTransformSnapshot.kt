package com.modose.app.ar.coordinates

data class ImageViewTransformSnapshot(
    val frameTimestampNanos: Long,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val viewWidthPx: Int,
    val viewHeightPx: Int,
    val transform: ImageViewTransform,
)

sealed interface ImageViewTransformFrameResult {
    data class Available(val snapshot: ImageViewTransformSnapshot) : ImageViewTransformFrameResult
    data class Unavailable(val reason: ImageViewTransformUnavailableReason) : ImageViewTransformFrameResult
    data class Rejected(val reason: CoordinateTransformFailureReason) : ImageViewTransformFrameResult
}

enum class ImageViewTransformUnavailableReason {
    SourceImageUnavailable,
    TrackingUnavailable,
}

object ImageViewTransformSnapshotFactory {
    fun fromBasis(
        frameTimestampNanos: Long,
        imageWidthPx: Int,
        imageHeightPx: Int,
        viewWidthPx: Int,
        viewHeightPx: Int,
        viewOrigin: ViewPixelPoint,
        viewImageXAxisEnd: ViewPixelPoint,
        viewImageYAxisEnd: ViewPixelPoint,
    ): ImageViewTransformFrameResult {
        if (
            frameTimestampNanos <= 0L ||
            imageWidthPx <= 0 ||
            imageHeightPx <= 0 ||
            viewWidthPx <= 0 ||
            viewHeightPx <= 0
        ) {
            return ImageViewTransformFrameResult.Rejected(
                CoordinateTransformFailureReason.InvalidDimensions,
            )
        }

        val transformResult = ImageViewTransform.create(
            m00 = (viewImageXAxisEnd.x - viewOrigin.x) / imageWidthPx,
            m01 = (viewImageYAxisEnd.x - viewOrigin.x) / imageHeightPx,
            m10 = (viewImageXAxisEnd.y - viewOrigin.y) / imageWidthPx,
            m11 = (viewImageYAxisEnd.y - viewOrigin.y) / imageHeightPx,
            translateX = viewOrigin.x,
            translateY = viewOrigin.y,
        )
        return when (transformResult) {
            is CoordinateTransformResult.Transformed -> ImageViewTransformFrameResult.Available(
                ImageViewTransformSnapshot(
                    frameTimestampNanos = frameTimestampNanos,
                    imageWidthPx = imageWidthPx,
                    imageHeightPx = imageHeightPx,
                    viewWidthPx = viewWidthPx,
                    viewHeightPx = viewHeightPx,
                    transform = transformResult.value,
                ),
            )
            is CoordinateTransformResult.Rejected -> ImageViewTransformFrameResult.Rejected(
                transformResult.reason,
            )
        }
    }
}
