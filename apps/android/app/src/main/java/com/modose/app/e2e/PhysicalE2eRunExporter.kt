package com.modose.app.e2e

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

enum class E2eObjectSet {
    A,
    B,
    C,
    D,
}

enum class E2eSurface {
    Wood,
    White,
    Black,
    Patterned,
    Venue,
}

data class E2eExportInput(
    val recordedAtUtc: String,
    val objectSet: E2eObjectSet,
    val surface: E2eSurface,
    val execution: E2eExecutionRecord,
    val positions: E2ePositionErrorReport,
    val video: E2eVideoEvidence,
)

data class ExportedE2eRun(
    val fileName: String,
    val json: String,
)

enum class E2eExportFailure {
    InvalidTimestamp,
    InvalidVideoEvidence,
    InconsistentObjectCount,
    InconsistentVerifiedResult,
    InvalidPositionError,
}

sealed interface E2eExportResult {
    data class Exported(val run: ExportedE2eRun) : E2eExportResult
    data class Rejected(val reason: E2eExportFailure) : E2eExportResult
}

object PhysicalE2eRunExporter {
    fun export(input: E2eExportInput): E2eExportResult {
        val execution = input.execution
        val metadata = execution.metadata
        if (!UTC_TIMESTAMP.matches(input.recordedAtUtc)) {
            return rejected(E2eExportFailure.InvalidTimestamp)
        }
        if (
            input.video.fileName != "${metadata.runId}.mp4" ||
            input.video.sizeBytes <= 0L ||
            !SHA256.matches(input.video.sha256)
        ) {
            return rejected(E2eExportFailure.InvalidVideoEvidence)
        }
        if (
            input.positions.samples.any {
                !it.errorCentimeters.isFinite() ||
                    it.errorCentimeters !in 0.0..MAX_ERROR_CENTIMETERS
            }
        ) {
            return rejected(E2eExportFailure.InvalidPositionError)
        }

        val resolvedCount =
            input.positions.samples.size + input.positions.unavailable.size
        val supportedResult = execution.finalResult !in setOf(
            E2eFinalResult.Rejected,
            E2eFinalResult.Aborted,
        )
        if (
            resolvedCount > metadata.objectCount ||
            (supportedResult && resolvedCount != metadata.objectCount)
        ) {
            return rejected(E2eExportFailure.InconsistentObjectCount)
        }
        if (
            execution.finalResult == E2eFinalResult.Verified &&
            (
                execution.matchResult != E2eMatchResult.Matched ||
                    input.positions.unavailable.isNotEmpty()
            )
        ) {
            return rejected(E2eExportFailure.InconsistentVerifiedResult)
        }

        val json = buildJsonObject {
            put("schemaVersion", JsonPrimitive("1.0"))
            put("runId", JsonPrimitive(metadata.runId))
            put("sceneId", JsonPrimitive(metadata.sceneId))
            put("recordedAt", JsonPrimitive(input.recordedAtUtc))
            put("appVersion", JsonPrimitive(metadata.appVersion))
            put("deviceModel", JsonPrimitive(metadata.deviceModel))
            put("modelId", JsonPrimitive(metadata.modelId))
            put("promptVersion", JsonPrimitive(metadata.promptVersion))
            put("testCaseId", JsonPrimitive(metadata.testCaseId))
            put("objectSet", JsonPrimitive(input.objectSet.name))
            put("surface", JsonPrimitive(input.surface.schemaValue()))
            put("objectCount", JsonPrimitive(metadata.objectCount))
            put(
                "latenciesMs",
                buildJsonObject {
                    putNullableLong(
                        "baseline",
                        execution.latencies.baselineMillis,
                    )
                    putNullableLong(
                        "compare",
                        execution.latencies.compareMillis,
                    )
                    putNullableLong(
                        "verify",
                        execution.latencies.verifyMillis,
                    )
                },
            )
            put("vlmCallCount", JsonPrimitive(execution.vlmCallCount))
            put("matchResult", JsonPrimitive(execution.matchResult.schemaValue()))
            put(
                "positionErrorsCm",
                buildJsonArray {
                    input.positions.samples.forEach {
                        add(JsonPrimitive(it.errorCentimeters))
                    }
                },
            )
            put("finalResult", JsonPrimitive(execution.finalResult.schemaValue()))
            put("falseSuccess", JsonPrimitive(execution.falseSuccess))
            put("videoFileName", JsonPrimitive(input.video.fileName))
        }.toString()

        return E2eExportResult.Exported(
            ExportedE2eRun(
                fileName = "${metadata.runId}.json",
                json = json,
            ),
        )
    }

    private fun JsonObjectBuilder.putNullableLong(
        key: String,
        value: Long?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun E2eSurface.schemaValue(): String =
        name.lowercase()

    private fun E2eMatchResult.schemaValue(): String =
        when (this) {
            E2eMatchResult.NotRun -> "not_run"
            else -> name.lowercase()
        }

    private fun E2eFinalResult.schemaValue(): String =
        when (this) {
            E2eFinalResult.NeedsCorrection -> "needs_correction"
            E2eFinalResult.ManualConfirmation -> "manual_confirmation"
            else -> name.lowercase()
        }

    private fun rejected(reason: E2eExportFailure) =
        E2eExportResult.Rejected(reason)

    private const val MAX_ERROR_CENTIMETERS = 200.0
    private val UTC_TIMESTAMP =
        Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
