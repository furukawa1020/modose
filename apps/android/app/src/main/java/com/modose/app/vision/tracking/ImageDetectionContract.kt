package com.modose.app.vision.tracking

import com.modose.app.core.NativeImageCapture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val lock = ReentrantLock()
    private var closed = false
    private var busy = false

    /** Called from GL: never wait for publication, close, or another producer. */
    fun acquire(): Boolean {
        if (!lock.tryLock()) return false
        try {
            if (closed || busy) return false
            busy = true
            return true
        } finally {
            lock.unlock()
        }
    }

    /** Returns true when resources can be disposed. Callback exceptions still release the slot. */
    fun finish(publish: () -> Unit): Boolean = lock.withLock {
        check(busy)
        try {
            if (!closed) publish()
        } finally {
            busy = false
        }
        closed
    }

    /** An in-flight detector must be disposed only after its completion callback. */
    fun close(): Boolean = lock.withLock {
        closed = true
        !busy
    }
}
