package com.modose.app.vision.tracking

import com.modose.app.network.baseline.BaselineObject

/** Immutable saved requirement, not a measured angle or a restoration verdict. */
internal class ConfirmedOrientationPolicy private constructor(
    private val sceneId: String,
    private val requiredById: Map<Int, Boolean>,
) {
    fun satisfiedWithoutMeasurement(sceneId: String, savedId: Int): Boolean =
        this.sceneId == sceneId && requiredById[savedId] == false

    companion object {
        fun create(
            sceneId: String, objects: List<BaselineObject>, objectIds: Map<String, Int>,
        ): ConfirmedOrientationPolicy {
            require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(sceneId))
            val entries = objects.map { it.id to it.orientationImportant }
            val ids = objectIds.toMap()
            require(entries.size in 1..5 && entries.map { it.first }.toSet().size == entries.size)
            require(entries.all { it.first.isNotBlank() && it.first.length <= 64 })
            require(ids.keys == entries.map { it.first }.toSet())
            require(ids.values.all { it > 0 } && ids.values.toSet().size == ids.size)
            return ConfirmedOrientationPolicy(sceneId,
                entries.associate { (external, required) -> ids.getValue(external) to required })
        }
    }
}
