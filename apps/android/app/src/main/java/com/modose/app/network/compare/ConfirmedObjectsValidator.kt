package com.modose.app.network.compare

import com.modose.app.network.baseline.BaselineAnalysisDecoder
import com.modose.app.network.baseline.BaselineDecodeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ValidatedConfirmedObjects(
    val json: String,
    val objectIds: Set<String>,
)

internal object ConfirmedObjectsValidator {
    private const val MAX_BYTES = 64_000
    private const val MAX_DEPTH = 8
    private val parser = Json {
        isLenient = false
        allowTrailingComma = false
        allowComments = false
    }

    fun validate(text: String): ValidatedConfirmedObjects? {
        // Bound work before allocating encoded bytes or recursively parsing JSON.
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return null
        }
        if (!hasBoundedDepth(text)) return null
        val root = try {
            parser.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        if (root.keys != setOf("objects", "excludedCandidates")) return null
        val envelope = buildJsonObject {
            put("schemaVersion", "1.0")
            put("status", "ok")
            put("modelId", "confirmed-local")
            put("promptVersion", "baseline-v1")
            put("repaired", false)
            put("objects", root.getValue("objects"))
            put("excludedCandidates", root.getValue("excludedCandidates"))
        }
        val decoded = BaselineAnalysisDecoder.decode(envelope.toString().toByteArray(Charsets.UTF_8))
            as? BaselineDecodeResult.Decoded ?: return null
        // Send the same parsed representation that passed the contract, not unchecked input.
        val normalized = root.toString()
        if (normalized.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return ValidatedConfirmedObjects(normalized, decoded.analysis.objects.map { it.id }.toSet())
    }

    private fun hasBoundedDepth(text: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else {
                when (character) {
                    '"' -> quoted = true
                    '{', '[' -> {
                        depth++
                        if (depth > MAX_DEPTH) return false
                    }
                    '}', ']' -> {
                        depth--
                        if (depth < 0) return false
                    }
                }
            }
        }
        return depth == 0 && !quoted
    }
}
