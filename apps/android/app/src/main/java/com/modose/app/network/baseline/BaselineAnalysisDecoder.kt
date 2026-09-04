package com.modose.app.network.baseline

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

data class BaselineAnalysis(
    val modelId: String,
    val repaired: Boolean,
    val objects: List<BaselineObject>,
    val excludedCandidates: List<ExcludedCandidate>,
)

data class BaselineObject(
    val id: String,
    val displayName: String,
    val appearanceFeatures: List<String>,
    val boundingBox: NormalizedBoundingBox,
    val orientationImportant: Boolean,
    val symmetry: ObjectSymmetry,
)

data class NormalizedBoundingBox(
    val yMin: Int,
    val xMin: Int,
    val yMax: Int,
    val xMax: Int,
)

enum class ObjectSymmetry {
    None,
    Bilateral,
    Rotational,
}

data class ExcludedCandidate(
    val displayName: String,
    val reason: ExclusionReason,
)

enum class ExclusionReason {
    Transparent,
    Reflective,
    Deformable,
    UnsupportedShape,
    Fixed,
    DuplicateAppearance,
}

enum class BaselineContractViolation {
    InvalidUtf8,
    InvalidJson,
    InvalidType,
    MissingField,
    UnknownField,
    InvalidConstant,
    InvalidLength,
    OutOfRange,
    InvalidBoundingBox,
    DuplicateObjectId,
}

sealed interface BaselineDecodeResult {
    data class Decoded(val analysis: BaselineAnalysis) : BaselineDecodeResult
    data class Rejected(val violation: BaselineContractViolation) : BaselineDecodeResult
}

object BaselineAnalysisDecoder {
    private val json = Json {
        isLenient = false
        allowTrailingComma = false
        allowComments = false
    }

    fun decode(body: ByteArray): BaselineDecodeResult {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            return rejected(BaselineContractViolation.InvalidUtf8)
        }

        val root = try {
            json.parseToJsonElement(text)
        } catch (_: RuntimeException) {
            return rejected(BaselineContractViolation.InvalidJson)
        }

        return try {
            BaselineDecodeResult.Decoded(Parser(root).parse())
        } catch (failure: ContractFailure) {
            rejected(failure.violation)
        }
    }

    private fun rejected(violation: BaselineContractViolation) =
        BaselineDecodeResult.Rejected(violation)

    private class Parser(
        private val root: JsonElement,
    ) {
        fun parse(): BaselineAnalysis {
            val value = root.objectValue()
            value.requireExactKeys(ROOT_FIELDS)
            value.requireConstant("schemaVersion", "1.0")
            value.requireConstant("status", "ok")
            value.requireConstant("promptVersion", "baseline-v1")
            val objects = value.arrayValue("objects")
            if (objects.size !in 1..5) fail(BaselineContractViolation.InvalidLength)
            val parsedObjects = objects.map(JsonElement::baselineObject)
            if (parsedObjects.map(BaselineObject::id).toSet().size != parsedObjects.size) {
                fail(BaselineContractViolation.DuplicateObjectId)
            }
            val excluded = value.arrayValue("excludedCandidates")
            if (excluded.size > 20) fail(BaselineContractViolation.InvalidLength)

            return BaselineAnalysis(
                modelId = value.stringValue("modelId", 1, 128),
                repaired = value.booleanValue("repaired"),
                objects = parsedObjects,
                excludedCandidates = excluded.map(JsonElement::excludedCandidate),
            )
        }

        private fun JsonElement.baselineObject(): BaselineObject {
            val value = objectValue()
            value.requireExactKeys(OBJECT_FIELDS)
            val features = value.arrayValue("appearanceFeatures")
            if (features.size !in 1..8) fail(BaselineContractViolation.InvalidLength)
            return BaselineObject(
                id = value.stringValue("id", 1, 64),
                displayName = value.stringValue("displayName", 1, 80),
                appearanceFeatures = features.map { it.stringValue(1, 120) },
                boundingBox = value.element("boundingBox").boundingBox(),
                orientationImportant = value.booleanValue("orientationImportant"),
                symmetry = when (value.stringValue("symmetry", 1, 16)) {
                    "none" -> ObjectSymmetry.None
                    "bilateral" -> ObjectSymmetry.Bilateral
                    "rotational" -> ObjectSymmetry.Rotational
                    else -> fail(BaselineContractViolation.InvalidConstant)
                },
            )
        }

        private fun JsonElement.boundingBox(): NormalizedBoundingBox {
            val value = objectValue()
            value.requireExactKeys(BOUNDING_BOX_FIELDS)
            val box = NormalizedBoundingBox(
                yMin = value.coordinate("yMin"),
                xMin = value.coordinate("xMin"),
                yMax = value.coordinate("yMax"),
                xMax = value.coordinate("xMax"),
            )
            if (box.yMin >= box.yMax || box.xMin >= box.xMax) {
                fail(BaselineContractViolation.InvalidBoundingBox)
            }
            return box
        }

        private fun JsonElement.excludedCandidate(): ExcludedCandidate {
            val value = objectValue()
            value.requireExactKeys(EXCLUDED_FIELDS)
            val reason = when (value.stringValue("reason", 1, 32)) {
                "transparent" -> ExclusionReason.Transparent
                "reflective" -> ExclusionReason.Reflective
                "deformable" -> ExclusionReason.Deformable
                "unsupported_shape" -> ExclusionReason.UnsupportedShape
                "fixed" -> ExclusionReason.Fixed
                "duplicate_appearance" -> ExclusionReason.DuplicateAppearance
                else -> fail(BaselineContractViolation.InvalidConstant)
            }
            return ExcludedCandidate(
                displayName = value.stringValue("displayName", 1, 80),
                reason = reason,
            )
        }

        private fun JsonObject.requireExactKeys(required: Set<String>) {
            if (!keys.containsAll(required)) fail(BaselineContractViolation.MissingField)
            if (keys.any { it !in required }) fail(BaselineContractViolation.UnknownField)
        }

        private fun JsonObject.requireConstant(name: String, expected: String) {
            if (stringValue(name, 1, 64) != expected) {
                fail(BaselineContractViolation.InvalidConstant)
            }
        }

        private fun JsonObject.element(name: String): JsonElement =
            this[name] ?: fail(BaselineContractViolation.MissingField)

        private fun JsonObject.stringValue(name: String, min: Int, max: Int): String =
            element(name).stringValue(min, max)

        private fun JsonElement.stringValue(min: Int, max: Int): String {
            val primitive = this as? JsonPrimitive
                ?: fail(BaselineContractViolation.InvalidType)
            if (!primitive.isString) fail(BaselineContractViolation.InvalidType)
            val value = primitive.content
            if (value.length !in min..max || value.isBlank()) {
                fail(BaselineContractViolation.InvalidLength)
            }
            return value
        }

        private fun JsonObject.booleanValue(name: String): Boolean {
            val primitive = element(name) as? JsonPrimitive
                ?: fail(BaselineContractViolation.InvalidType)
            if (primitive.isString) fail(BaselineContractViolation.InvalidType)
            return primitive.booleanOrNull ?: fail(BaselineContractViolation.InvalidType)
        }

        private fun JsonObject.arrayValue(name: String): JsonArray =
            element(name) as? JsonArray ?: fail(BaselineContractViolation.InvalidType)

        private fun JsonElement.objectValue(): JsonObject =
            this as? JsonObject ?: fail(BaselineContractViolation.InvalidType)

        private fun JsonObject.coordinate(name: String): Int {
            val primitive = element(name) as? JsonPrimitive
                ?: fail(BaselineContractViolation.InvalidType)
            if (primitive.isString) fail(BaselineContractViolation.InvalidType)
            val value = primitive.intOrNull ?: fail(BaselineContractViolation.InvalidType)
            if (value !in 0..1000) fail(BaselineContractViolation.OutOfRange)
            return value
        }
    }

    private class ContractFailure(
        val violation: BaselineContractViolation,
    ) : RuntimeException()

    private fun fail(violation: BaselineContractViolation): Nothing =
        throw ContractFailure(violation)

    private val ROOT_FIELDS = setOf(
        "schemaVersion",
        "status",
        "modelId",
        "promptVersion",
        "repaired",
        "objects",
        "excludedCandidates",
    )
    private val OBJECT_FIELDS = setOf(
        "id",
        "displayName",
        "appearanceFeatures",
        "boundingBox",
        "orientationImportant",
        "symmetry",
    )
    private val BOUNDING_BOX_FIELDS = setOf("yMin", "xMin", "yMax", "xMax")
    private val EXCLUDED_FIELDS = setOf("displayName", "reason")
}
