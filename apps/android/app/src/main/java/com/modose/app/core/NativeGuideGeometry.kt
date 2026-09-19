package com.modose.app.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

internal sealed interface NativeGuideDrawData {
    data object Empty : NativeGuideDrawData
    data object Invalid : NativeGuideDrawData
    data class Lines(val vertices: FloatArray, val orientationCheck: Boolean) : NativeGuideDrawData
}

/** Builds at most 64 line vertices. It does not select objects or decide success. */
internal object NativeGuideGeometry {
    fun build(frame: NativeWorldFramePresentation): NativeGuideDrawData {
        if (frame !is NativeWorldFramePresentation.Ready) return NativeGuideDrawData.Empty
        val guide = frame.guidance ?: return NativeGuideDrawData.Empty
        val points = ArrayList<CoreVector>(64)
        when (guide) {
            is NativeWorldGuidance.Recover -> return NativeGuideDrawData.Empty
            is NativeWorldGuidance.Move -> {
                val dx = guide.to.x - guide.from.x
                val dy = guide.to.y - guide.from.y
                val dz = guide.to.z - guide.from.z
                val horizontal = hypot(dx, dz)
                val length = hypot(horizontal, dy)
                if (!length.isFinite() || !horizontal.isFinite() || horizontal <= 0.0) {
                    return NativeGuideDrawData.Invalid
                }
                val head = min(0.04, length * 0.25)
                fun wing(side: Double) = CoreVector(
                    guide.to.x - dx / length * head - dz / horizontal * head * 0.5 * side,
                    guide.to.y - dy / length * head,
                    guide.to.z - dz / length * head + dx / horizontal * head * 0.5 * side,
                )
                points.addAll(listOf(guide.from, guide.to, guide.to, wing(1.0), guide.to, wing(-1.0)))
            }
            is NativeWorldGuidance.Ring -> {
                repeat(32) { segment ->
                    points.add(onPlane(guide.target, cos(segment * PI / 16) * 0.03,
                        sin(segment * PI / 16) * 0.03))
                    points.add(onPlane(guide.target, cos((segment + 1) * PI / 16) * 0.03,
                        sin((segment + 1) * PI / 16) * 0.03))
                }
            }
            is NativeWorldGuidance.CheckOrientation -> {
                points.addAll(listOf(
                    onPlane(guide.target, -0.025, 0.0), onPlane(guide.target, 0.025, 0.0),
                    onPlane(guide.target, 0.0, -0.025), onPlane(guide.target, 0.0, 0.025),
                ))
            }
        }
        val vertices = FloatArray(points.size * 3)
        for ((i, point) in points.withIndex()) {
            vertices[i * 3] = point.x.toFloat()
            vertices[i * 3 + 1] = point.y.toFloat()
            vertices[i * 3 + 2] = point.z.toFloat()
        }
        if (vertices.any { !it.isFinite() }) return NativeGuideDrawData.Invalid
        return NativeGuideDrawData.Lines(vertices, guide is NativeWorldGuidance.CheckOrientation)
    }

    private fun onPlane(target: NativeWorldTarget, x: Double, z: Double) = CoreVector(
        target.position.x + target.xAxis.x * x + target.zAxis.x * z,
        target.position.y + target.xAxis.y * x + target.zAxis.y * z,
        target.position.z + target.xAxis.z * x + target.zAxis.z * z,
    )
}
