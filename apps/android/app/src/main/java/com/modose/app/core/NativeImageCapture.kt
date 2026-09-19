package com.modose.app.core

import kotlin.math.abs
import kotlin.math.hypot

/** Raw, unrotated, uncropped CPU image pixels, not VLM normalized coordinates. */
internal data class NativeImageBox(
    val currentId: Int, val left: Double, val top: Double, val right: Double, val bottom: Double,
    val occludesOther: Boolean,
)

internal data class NativeImageRecognition(
    val epoch: NativeGuidanceEpoch,
    val imageTimestampNanos: Long,
    val boxes: List<NativeImageBox>,
    val evidence: List<CorePairEvidence>,
)

internal enum class NativeImageProjectionError {
    FOREIGN_STREAM, IMAGE_MISMATCH, INVALID_BOX, INVALID_OBJECT_SET, OUTSIDE_VIEW, INVALID_RAY,
}

internal sealed interface NativeImageProjection {
    data class Projected(val detections: List<CoreDetection>) : NativeImageProjection
    data class Rejected(val reason: NativeImageProjectionError) : NativeImageProjection
}

internal data class NativeImageSubmission(
    val projectionError: NativeImageProjectionError?,
    val processing: NativeFrameProcessing,
)

/** Immutable capture metadata; retains no image bytes or Android frame objects. */
internal class NativeImageCapture private constructor(
    val epoch: NativeGuidanceEpoch,
    val observedAtMs: Long,
    val imageTimestampNanos: Long,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val viewWidth: Int,
    private val viewHeight: Int,
    private val imageToView: DoubleArray,
    private val inverseViewProjection: DoubleArray,
) {
    fun project(input: NativeImageRecognition): NativeImageProjection {
        if (input.epoch !== epoch) return reject(NativeImageProjectionError.FOREIGN_STREAM)
        if (input.imageTimestampNanos != imageTimestampNanos) {
            return reject(NativeImageProjectionError.IMAGE_MISMATCH)
        }
        if (input.boxes.size > 5) return reject(NativeImageProjectionError.INVALID_OBJECT_SET)
        val boxes = input.boxes.toList()
        val ids = HashSet<Int>()
        val detections = ArrayList<CoreDetection>(boxes.size)
        for (box in boxes) {
            if (box.currentId <= 0 || !ids.add(box.currentId)) {
                return reject(NativeImageProjectionError.INVALID_OBJECT_SET)
            }
            if (listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() } ||
                box.left < 0 || box.top < 0 || box.right > imageWidth || box.bottom > imageHeight ||
                box.left >= box.right || box.top >= box.bottom
            ) return reject(NativeImageProjectionError.INVALID_BOX)
            val x = (box.left + box.right) * 0.5
            val y = (box.top + box.bottom) * 0.5
            val vx = imageToView[0] * x + imageToView[1] * y + imageToView[4]
            val vy = imageToView[2] * x + imageToView[3] * y + imageToView[5]
            if (!vx.isFinite() || !vy.isFinite() || vx < 0 || vy < 0 ||
                vx > viewWidth || vy > viewHeight
            ) return reject(NativeImageProjectionError.OUTSIDE_VIEW)
            val nx = 2.0 * vx / viewWidth - 1.0
            val ny = 1.0 - 2.0 * vy / viewHeight
            val near = unproject(nx, ny, -1.0) ?: return reject(NativeImageProjectionError.INVALID_RAY)
            val far = unproject(nx, ny, 1.0) ?: return reject(NativeImageProjectionError.INVALID_RAY)
            val dx = far.x - near.x
            val dy = far.y - near.y
            val dz = far.z - near.z
            val length = hypot(hypot(dx, dy), dz)
            if (!length.isFinite() || length <= 1e-12) return reject(NativeImageProjectionError.INVALID_RAY)
            detections.add(CoreDetection(box.currentId, near,
                CoreVector(dx / length, dy / length, dz / length), box.occludesOther))
        }
        return NativeImageProjection.Projected(detections)
    }

    private fun unproject(x: Double, y: Double, z: Double): CoreVector? {
        val m = inverseViewProjection
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (!w.isFinite() || abs(w) <= 1e-12) return null
        return CoreVector(
            (m[0] * x + m[4] * y + m[8] * z + m[12]) / w,
            (m[1] * x + m[5] * y + m[9] * z + m[13]) / w,
            (m[2] * x + m[6] * y + m[10] * z + m[14]) / w,
        ).takeIf { it.isFinite() }
    }

    private fun reject(reason: NativeImageProjectionError) = NativeImageProjection.Rejected(reason)

    companion object {
        /** Inverse matrix must come from a successful matrix inversion at capture time. */
        fun create(
            epoch: NativeGuidanceEpoch, observedAtMs: Long, imageTimestampNanos: Long,
            imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int,
            imageToView: DoubleArray, inverseViewProjection: DoubleArray,
        ): NativeImageCapture? {
            if (observedAtMs < 0 || imageTimestampNanos <= 0 ||
                minOf(imageWidth, imageHeight, viewWidth, viewHeight) <= 0 ||
                imageToView.size != 6 || inverseViewProjection.size != 16
            ) return null
            val transform = imageToView.copyOf()
            val inverse = inverseViewProjection.copyOf()
            if (transform.any { !it.isFinite() } || inverse.any { !it.isFinite() }) return null
            val determinant = transform[0] * transform[3] - transform[1] * transform[2]
            if (!determinant.isFinite() || abs(determinant) <= 1e-12) return null
            return NativeImageCapture(epoch, observedAtMs, imageTimestampNanos,
                imageWidth, imageHeight, viewWidth, viewHeight, transform, inverse)
        }
    }
}
