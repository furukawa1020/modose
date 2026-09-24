package com.modose.app.network.compare

import com.modose.app.network.baseline.*
import kotlinx.serialization.json.*

internal object ConfirmedObjectsEncoder {
    fun encode(objects: List<BaselineObject>): String? {
        if (objects.size !in 1..5 || objects.any { it.appearanceFeatures.size !in 1..8 }) return null
        val values = JsonArray(objects.map { item ->
            buildJsonObject {
                put("id", item.id)
                put("displayName", item.displayName)
                put("appearanceFeatures", JsonArray(item.appearanceFeatures.map(::JsonPrimitive)))
                put("boundingBox", buildJsonObject {
                    put("yMin", item.boundingBox.yMin); put("xMin", item.boundingBox.xMin)
                    put("yMax", item.boundingBox.yMax); put("xMax", item.boundingBox.xMax)
                })
                put("orientationImportant", item.orientationImportant)
                put("symmetry", when (item.symmetry) {
                    ObjectSymmetry.None -> "none"
                    ObjectSymmetry.Bilateral -> "bilateral"
                    ObjectSymmetry.Rotational -> "rotational"
                })
            }
        })
        // Reuse the baseline contract validator, including lengths and duplicate IDs.
        val validation = buildJsonObject {
            put("schemaVersion", "1.0"); put("status", "ok"); put("modelId", "confirmed-local")
            put("promptVersion", "baseline-v1"); put("repaired", false)
            put("objects", values); put("excludedCandidates", JsonArray(emptyList()))
        }
        if (BaselineAnalysisDecoder.decode(validation.toString().toByteArray()) !is BaselineDecodeResult.Decoded) return null
        return buildJsonObject {
            put("objects", values); put("excludedCandidates", JsonArray(emptyList()))
        }.toString().takeIf { it.toByteArray().size <= 64_000 }
    }
}
