package com.modose.app.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One frame's rigid change of world coordinates for the SAME anchor.
 * Matrices are column-major. Capture rays enter saved world; rendering leaves it.
 * The caller binds both poses to the same anchor ID and the current camera frame.
 */
internal class NativeAnchorRebase private constructor(
    private val currentToSaved: DoubleArray,
    private val savedToCurrent: DoubleArray,
) {
    fun rebaseInverseViewProjection(current: DoubleArray): DoubleArray? =
        multiply(currentToSaved, current)

    fun rebaseView(current: DoubleArray): DoubleArray? =
        multiply(current, savedToCurrent)

    companion object {
        /** Pose layout: translation xyz, quaternion xyzw. Reject corrupt, non-unit poses. */
        fun create(savedPose: DoubleArray, currentPose: DoubleArray): NativeAnchorRebase? {
            val saved = poseMatrix(savedPose) ?: return null
            val current = poseMatrix(currentPose) ?: return null
            val toSaved = multiply(saved, inverseRigid(current)) ?: return null
            val toCurrent = multiply(current, inverseRigid(saved)) ?: return null
            return NativeAnchorRebase(toSaved, toCurrent)
        }

        private fun poseMatrix(input: DoubleArray): DoubleArray? {
            if (input.size != 7) return null
            val p = input.copyOf()
            if (p.any { !it.isFinite() }) return null
            val norm = sqrt(p[3] * p[3] + p[4] * p[4] + p[5] * p[5] + p[6] * p[6])
            if (!norm.isFinite() || abs(norm - 1.0) > 1e-3) return null
            val x = p[3] / norm
            val y = p[4] / norm
            val z = p[5] / norm
            val w = p[6] / norm
            return doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
                p[0], p[1], p[2], 1.0,
            )
        }

        private fun inverseRigid(matrix: DoubleArray): DoubleArray {
            val inverse = DoubleArray(16)
            for (column in 0..2) for (row in 0..2) {
                inverse[column * 4 + row] = matrix[row * 4 + column]
            }
            for (row in 0..2) {
                inverse[12 + row] = -(inverse[row] * matrix[12] +
                    inverse[4 + row] * matrix[13] + inverse[8 + row] * matrix[14])
            }
            inverse[15] = 1.0
            return inverse
        }

        private fun multiply(left: DoubleArray, right: DoubleArray): DoubleArray? {
            if (left.size != 16 || right.size != 16 ||
                left.any { !it.isFinite() } || right.any { !it.isFinite() }
            ) return null
            val result = DoubleArray(16)
            for (column in 0..3) for (row in 0..3) {
                for (k in 0..3) result[column * 4 + row] += left[k * 4 + row] * right[column * 4 + k]
            }
            return result.takeIf { values -> values.all { it.isFinite() } }
        }
    }
}
