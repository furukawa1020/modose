package com.modose.app.core

/** Same-capture geometry and anchor-relative targets. Never reuse its world basis in a later frame. */
internal class NativeBaselineTargets private constructor(
    private val geometry: DoubleArray,
    private val ids: IntArray,
    private val positions: DoubleArray,
) {
    val size: Int get() = ids.size
    fun copyGeometry(): DoubleArray = geometry.copyOf()
    fun copyIds(): IntArray = ids.copyOf()
    fun copyPositions(): DoubleArray = positions.copyOf()

    companion object {
        /** Throws on any rejected object. There is deliberately no partial-success result. */
        fun project(geometry: DoubleArray, detections: List<CoreDetection>): NativeBaselineTargets {
            require(geometry.size in 15..137 && (geometry.size - 9) % 2 == 0)
            require(detections.size in 1..5)
            val objects = detections.toList()
            val ids = objects.map { it.currentId }.toIntArray()
            require(ids.all { it > 0 } && ids.toSet().size == ids.size)
            val rays = DoubleArray(objects.size * 6)
            objects.forEachIndexed { index, item ->
                require(item.origin.isFinite() && item.direction.isFinite())
                val offset = index * 6
                rays[offset] = item.origin.x
                rays[offset + 1] = item.origin.y
                rays[offset + 2] = item.origin.z
                rays[offset + 3] = item.direction.x
                rays[offset + 4] = item.direction.y
                rays[offset + 5] = item.direction.z
            }
            val captured = geometry.copyOf()
            val positions = NativeSceneBindings.nativeProjectTargets(captured, rays)
            check(positions.size == ids.size * 2 && positions.all { it.isFinite() })
            return NativeBaselineTargets(captured, ids, positions)
        }
    }
}
