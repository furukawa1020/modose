package com.modose.app.vision.tracking

import com.modose.app.core.NativeImageCapture

/** Pixel coordinates in the original, unrotated CPU image. IDs are not scene object IDs. */
internal data class DetectedImageObject(
    val trackingId: Int?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal enum class DetectionFailure {
    INVALID_IMAGE, CONVERSION, DETECTOR,
}

internal sealed interface ImageDetectionResult {
    data class Detected(val objects: List<DetectedImageObject>) : ImageDetectionResult
    data class Failed(val reason: DetectionFailure) : ImageDetectionResult
}

internal fun interface ImageDetectionSink {
    /** Runs on the detector worker. Must return promptly and must not wait for the UI thread. */
    fun accept(capture: NativeImageCapture, result: ImageDetectionResult)
}

/** No queued frames. Publication and close are serialized so close revokes late results. */
internal class DetectionFlight {
    private var closed = false
    private var busy = false

    @Synchronized
    fun acquire(): Boolean {
        if (closed || busy) return false
        busy = true
        return true
    }

    /** Returns true when resources can be disposed. Callback exceptions still release the slot. */
    @Synchronized
    fun finish(publish: () -> Unit): Boolean {
        check(busy)
        try {
            if (!closed) publish()
        } finally {
            busy = false
        }
        return closed
    }

    /** An in-flight detector must be disposed only after its completion callback. */
    @Synchronized
    fun close(): Boolean {
        closed = true
        return !busy
    }
}
