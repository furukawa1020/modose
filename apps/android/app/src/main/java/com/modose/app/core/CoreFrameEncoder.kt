package com.modose.app.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class CoreVector(val x: Double, val y: Double, val z: Double) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

internal data class CoreDetection(
    val currentId: Int,
    val origin: CoreVector,
    val direction: CoreVector,
    val occludesOther: Boolean,
)

internal data class CorePairEvidence(
    val savedId: Int,
    val currentId: Int,
    val semanticScore: Double,
    val embeddingScore: Double,
    val signatureScore: Double,
    val orientationAligned: Boolean,
)

internal enum class FrameEncodingError {
    TOO_MANY_OBJECTS,
    TOO_MANY_PAIRS,
    INVALID_ID,
    DUPLICATE_ID,
    DUPLICATE_PAIR,
    UNKNOWN_CURRENT_ID,
    INVALID_RAY,
    INVALID_SCORE,
}

internal sealed interface FrameEncoding {
    data class Encoded(val bytes: ByteArray) : FrameEncoding
    data class Rejected(val reason: FrameEncodingError) : FrameEncoding
}

/** MDFR v1. No Android objects, pointers, handles or timestamps are serialized. */
internal object CoreFrameEncoder {
    fun encode(
        trackingValid: Boolean,
        detections: List<CoreDetection>,
        evidence: List<CorePairEvidence>,
    ): FrameEncoding {
        if (detections.size > 5) return reject(FrameEncodingError.TOO_MANY_OBJECTS)
        if (evidence.size > 25) return reject(FrameEncodingError.TOO_MANY_PAIRS)
        // Callers serialize access to the session and its input lists.
        val objects = detections.toList()
        val pairs = evidence.toList()
        val ids = HashSet<Int>()
        for (item in objects) {
            if (item.currentId <= 0) return reject(FrameEncodingError.INVALID_ID)
            if (!ids.add(item.currentId)) return reject(FrameEncodingError.DUPLICATE_ID)
            if (!item.origin.isFinite() || !item.direction.isFinite() ||
                (item.direction.x == 0.0 && item.direction.y == 0.0 && item.direction.z == 0.0)
            ) return reject(FrameEncodingError.INVALID_RAY)
        }
        val keys = HashSet<Pair<Int, Int>>()
        for (pair in pairs) {
            if (pair.savedId <= 0 || pair.currentId <= 0) {
                return reject(FrameEncodingError.INVALID_ID)
            }
            if (pair.currentId !in ids) return reject(FrameEncodingError.UNKNOWN_CURRENT_ID)
            if (!keys.add(pair.savedId to pair.currentId)) {
                return reject(FrameEncodingError.DUPLICATE_PAIR)
            }
            if (!validScore(pair.semanticScore) || !validScore(pair.embeddingScore) ||
                !validScore(pair.signatureScore)
            ) return reject(FrameEncodingError.INVALID_SCORE)
        }
        val buffer = ByteBuffer.allocate(12 + objects.size * 53 + pairs.size * 33)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(byteArrayOf(0x4d, 0x44, 0x46, 0x52))
        buffer.putShort(1.toShort())
        buffer.put(objects.size.toByte())
        buffer.put(pairs.size.toByte())
        buffer.putBoolean(trackingValid)
        buffer.put(byteArrayOf(0, 0, 0))
        for (item in objects) {
            buffer.putInt(item.currentId)
            buffer.putVector(item.origin)
            buffer.putVector(item.direction)
            buffer.putBoolean(item.occludesOther)
        }
        for (pair in pairs) {
            buffer.putInt(pair.savedId)
            buffer.putInt(pair.currentId)
            buffer.putDouble(pair.semanticScore)
            buffer.putDouble(pair.embeddingScore)
            buffer.putDouble(pair.signatureScore)
            buffer.putBoolean(pair.orientationAligned)
        }
        return FrameEncoding.Encoded(buffer.array())
    }

    /** Fresh storage on every call: a transport cannot mutate future invalidations. */
    fun trackingLost(): ByteArray = byteArrayOf(
        0x4d, 0x44, 0x46, 0x52, 0, 1, 0, 0, 0, 0, 0, 0,
    )

    private fun reject(reason: FrameEncodingError) = FrameEncoding.Rejected(reason)

    private fun validScore(value: Double): Boolean = value.isFinite() && value in 0.0..1.0

    private fun ByteBuffer.putBoolean(value: Boolean) {
        put(if (value) 1.toByte() else 0.toByte())
    }

    private fun ByteBuffer.putVector(value: CoreVector) {
        putDouble(value.x)
        putDouble(value.y)
        putDouble(value.z)
    }
}

/**
 * Implementations must apply synchronously, throw on native failure, and never
 * translate transport failure into a successful restoration result.
 */
internal fun interface CoreFrameTransport<T> {
    fun apply(handle: Long, observedAtMs: Long, bytes: ByteArray): T
}

internal sealed interface FrameSubmission<out T> {
    data class Applied<T>(val state: T) : FrameSubmission<T>
    data class Invalidated<T>(
        val reason: FrameEncodingError,
        val state: T,
    ) : FrameSubmission<T>
}

/**
 * One dispatcher per owning session thread. The owner must stop guidance on
 * exceptions, including invalid handles/clocks; no last-success fallback exists.
 * Times are monotonic session milliseconds, never wall-clock timestamps.
 */
internal class CoreFrameDispatcher<T>(private val transport: CoreFrameTransport<T>) {
    fun submit(
        handle: Long,
        observedAtMs: Long,
        trackingValid: Boolean,
        detections: List<CoreDetection>,
        evidence: List<CorePairEvidence>,
    ): FrameSubmission<T> {
        require(handle > 0) { "Invalid native session handle" }
        require(observedAtMs >= 0) { "Invalid observation timestamp" }
        return when (val encoded = CoreFrameEncoder.encode(trackingValid, detections, evidence)) {
            is FrameEncoding.Encoded ->
                FrameSubmission.Applied(transport.apply(handle, observedAtMs, encoded.bytes))
            is FrameEncoding.Rejected -> FrameSubmission.Invalidated(
                encoded.reason,
                transport.apply(handle, observedAtMs, CoreFrameEncoder.trackingLost()),
            )
        }
    }
}
