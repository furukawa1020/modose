package com.modose.app.vision.tracking

import com.modose.app.ar.image.CpuCameraImage

internal interface AppearanceExtractor : AutoCloseable {
    fun extract(image: CpuCameraImage, box: DetectedImageObject): AppearanceExtractionResult
}

internal enum class AppearanceExtractionFailure {
    WRONG_THREAD, CLOSED, INVALID_IMAGE, INVALID_OUTPUT, INFERENCE,
    INVALID_MODEL, MODEL_UNAVAILABLE, HASH_MISMATCH, MODEL_INITIALIZATION,
}

internal sealed interface AppearanceExtractionResult {
    data class Extracted(
        val imageTimestampNanos: Long,
        val box: DetectedImageObject,
        val appearance: MeasuredAppearance,
    ) : AppearanceExtractionResult
    data class Rejected(val reason: AppearanceExtractionFailure) : AppearanceExtractionResult
}

internal sealed interface AppearanceExtractorOpen {
    data class Opened(val extractor: AppearanceExtractor) : AppearanceExtractorOpen
    data class Rejected(val reason: AppearanceExtractionFailure) : AppearanceExtractorOpen
}

