package com.modose.app.network.compare

import com.modose.app.flow.compare.CurrentObjectState
import com.modose.app.network.baseline.NormalizedBoundingBox
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal data class ComparedObject(
    val baselineObjectId: String, val state: CurrentObjectState, val confidence: Double,
    val currentBox: NormalizedBoundingBox?, val ambiguityReason: String,
)
internal data class AddedCompareObject(
    val displayName: String, val currentBox: NormalizedBoundingBox, val confidence: Double,
)
internal data class CompareAnalysis(
    val modelId: String, val repaired: Boolean,
    val matches: List<ComparedObject>, val addedObjects: List<AddedCompareObject>,
)

/** The HTTP contract, not the older angle/occlusion-enriched presentation model. */
internal object CompareAnalysisDecoder {
    fun decode(body: ByteArray, expectedIds: List<String>): CompareAnalysis? {
        if (body.size !in 1..64_000 || expectedIds.size !in 1..5 ||
            expectedIds.any { it.isBlank() } || expectedIds.toSet().size != expectedIds.size
        ) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
            require(boundedDepth(text))
            val root = Json.parseToJsonElement(text).obj(
                "schemaVersion", "status", "modelId", "promptVersion", "repaired", "matches", "addedObjects")
            require(root.string("schemaVersion") == "1.0" && root.string("status") == "ok")
            require(root.string("promptVersion") == "compare-v1")
            val model = root.string("modelId").also { require(it.isNotBlank() && it.length <= 128) }
            val repaired = root.getValue("repaired").primitive().also { require(!it.isString) }
                .booleanOrNull ?: error("Invalid boolean")
            val matches = root.getValue("matches").array(1, 5).map { element ->
                val value = element.obj("baselineObjectId", "state", "confidence", "currentBox", "ambiguityReason")
                val id = value.string("baselineObjectId").also { require(it in expectedIds) }
                val state = when (value.string("state")) {
                    "aligned" -> CurrentObjectState.Aligned
                    "moved" -> CurrentObjectState.Moved
                    "rotated" -> CurrentObjectState.Rotated
                    "moved_rotated" -> CurrentObjectState.MovedRotated
                    "missing" -> CurrentObjectState.Missing
                    "occluded" -> CurrentObjectState.Occluded
                    "ambiguous" -> CurrentObjectState.Ambiguous
                    else -> error("Unknown state")
                }
                val box = value.getValue("currentBox").let { if (it == JsonNull) null else it.box() }
                val reason = value.string("ambiguityReason").also { require(it.length <= 1024) }
                if (state in setOf(CurrentObjectState.Aligned, CurrentObjectState.Moved,
                        CurrentObjectState.Rotated, CurrentObjectState.MovedRotated)) require(box != null)
                if (state == CurrentObjectState.Missing) require(box == null)
                if (state == CurrentObjectState.Ambiguous) require(reason.isNotBlank()) else require(reason.isEmpty())
                ComparedObject(id, state, value.score(), box, reason)
            }
            require(matches.map { it.baselineObjectId }.toSet() == expectedIds.toSet() &&
                matches.size == expectedIds.size)
            val added = root.getValue("addedObjects").array(0, 20).map {
                val value = it.obj("displayName", "currentBox", "confidence")
                val name = value.string("displayName").also { require(it.isNotBlank() && it.length <= 80) }
                AddedCompareObject(name, value.getValue("currentBox").box(), value.score())
            }
            CompareAnalysis(model, repaired, expectedIds.map { id -> matches.single { it.baselineObjectId == id } }, added)
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.obj(vararg keys: String): JsonObject =
        (this as? JsonObject ?: error("Invalid object")).also { require(it.keys == keys.toSet()) }
    private fun JsonElement.primitive() = this as? JsonPrimitive ?: error("Invalid primitive")
    private fun JsonObject.string(key: String): String =
        getValue(key).primitive().also { require(it.isString) }.content
    private fun JsonElement.array(min: Int, max: Int): JsonArray =
        (this as? JsonArray ?: error("Invalid array")).also { require(it.size in min..max) }
    private fun JsonObject.score(): Double {
        val value = getValue("confidence").primitive().also { require(!it.isString) }.doubleOrNull
            ?: error("Invalid confidence")
        require(value.isFinite() && value in 0.0..1.0)
        return value
    }
    private fun JsonElement.box(): NormalizedBoundingBox {
        val value = obj("yMin", "xMin", "yMax", "xMax")
        fun coordinate(key: String): Int = (value.getValue(key).primitive()
            .also { require(!it.isString) }.intOrNull ?: error("Invalid coordinate"))
            .also { require(it in 0..1000) }
        return NormalizedBoundingBox(coordinate("yMin"), coordinate("xMin"),
            coordinate("yMax"), coordinate("xMax")).also { require(it.yMin < it.yMax && it.xMin < it.xMax) }
    }

    private fun boundedDepth(text: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (ch in text) {
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 8) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return depth == 0 && !quoted
    }
}
