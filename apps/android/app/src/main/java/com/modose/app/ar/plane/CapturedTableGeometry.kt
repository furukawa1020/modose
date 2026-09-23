package com.modose.app.ar.plane

import kotlin.math.abs
import kotlin.math.hypot

/** Captured basis is for rays from this frame only, never a later world-space frame. */
class CapturedTableGeometry private constructor(
    val frameTimestampNanos: Long,
    val anchorId: Long,
    private val values: DoubleArray,
) {
    fun copyFor(timestampNanos: Long, expectedAnchorId: Long): DoubleArray? =
        if (timestampNanos == frameTimestampNanos && expectedAnchorId == anchorId) values.copyOf()
        else null

    companion object {
        /** Rust geometry layout: origin xyz, X axis xyz, Z axis xyz, boundary x/z pairs. */
        fun create(timestampNanos: Long, anchorId: Long, geometry: DoubleArray): CapturedTableGeometry? {
            if (timestampNanos <= 0 || geometry.size !in 15..137 || (geometry.size - 9) % 2 != 0) return null
            val v = geometry.copyOf()
            if (v.any { !it.isFinite() }) return null
            val xLength = hypot(hypot(v[3], v[4]), v[5])
            val zLength = hypot(hypot(v[6], v[7]), v[8])
            val dot = v[3] * v[6] + v[4] * v[7] + v[5] * v[8]
            if (abs(xLength - 1.0) > 1e-6 || abs(zLength - 1.0) > 1e-6 ||
                !dot.isFinite() || abs(dot) > 1e-6) return null
            val nx = v[4] * v[8] - v[5] * v[7]
            val ny = v[5] * v[6] - v[3] * v[8]
            val nz = v[3] * v[7] - v[4] * v[6]
            if (abs(ny) / hypot(hypot(nx, ny), nz) < 0.999) return null
            val count = (v.size - 9) / 2
            fun x(i: Int) = v[9 + 2 * (i % count)]
            fun z(i: Int) = v[10 + 2 * (i % count)]
            var area = 0.0
            for (i in 0 until count) {
                for (j in 0 until i) if (x(i) == x(j) && z(i) == z(j)) return null
                area += x(i) * z(i + 1) - z(i) * x(i + 1)
            }
            if (!area.isFinite() || abs(area) <= 1e-12) return null
            val winding = if (area > 0) 1.0 else -1.0
            for (i in 0 until count) for (j in 0 until count) {
                val side = ((x(i + 1) - x(i)) * (z(j) - z(i)) -
                    (z(i + 1) - z(i)) * (x(j) - x(i))) * winding
                if (!side.isFinite() || side < 0.0) return null
            }
            return CapturedTableGeometry(timestampNanos, anchorId, v)
        }
    }
}
