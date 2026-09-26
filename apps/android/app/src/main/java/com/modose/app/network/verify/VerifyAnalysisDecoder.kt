package com.modose.app.network.verify

import com.modose.app.flow.verification.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** Decodes the Go HTTP envelope. Semantic verdict validation remains in the use case. */
object VerifyAnalysisDecoder : SceneVerificationDecoder {
    override fun decode(body: ByteArray): VerificationDecodeResult {
        if (body.size !in 1..64_000) return rejected(VerificationDecodeFailure.ContractMismatch)
        val root = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
            require(boundedDepth(text))
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            return rejected(VerificationDecodeFailure.MalformedJson)
        }
        return try {
            val value = root.obj("schemaVersion", "status", "modelId", "promptVersion",
                "repaired", "verificationStatus", "corrections", "uncertaintyReason")
            val repaired = value.getValue("repaired") as? JsonPrimitive ?: error("Invalid boolean")
            require(!repaired.isString && repaired.booleanOrNull != null)
            // Go encodes a nil correction slice as null. Both forms mean no corrections.
            val corrections = when (val array = value.getValue("corrections")) {
                JsonNull -> emptyList()
                is JsonArray -> {
                    require(array.size <= 5)
                    array.map {
                        val correction = it.obj("baselineObjectId", "reason")
                        RawVerificationCorrection(correction.string("baselineObjectId", 64, false),
                            correction.string("reason", 1024, false))
                    }
                }
                else -> error("Invalid corrections")
            }
            VerificationDecodeResult.Decoded(RawSceneVerification(
                value.string("schemaVersion", 16, false), value.string("status", 32, false),
                value.string("modelId", 128, false), value.string("promptVersion", 64, false),
                value.string("verificationStatus", 32, false), corrections,
                value.string("uncertaintyReason", 1024, true)))
        } catch (_: Exception) {
            rejected(VerificationDecodeFailure.ContractMismatch)
        }
    }

    private fun JsonElement.obj(vararg keys: String): JsonObject =
        (this as? JsonObject ?: error("Invalid object")).also { require(it.keys == keys.toSet()) }

    private fun JsonObject.string(key: String, max: Int, emptyAllowed: Boolean): String {
        val value = getValue(key) as? JsonPrimitive ?: error("Invalid string")
        require(value.isString && value.content.length <= max)
        if (!emptyAllowed) require(value.content.isNotBlank())
        return value.content
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

    private fun rejected(reason: VerificationDecodeFailure) = VerificationDecodeResult.Rejected(reason)
}
