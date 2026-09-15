package com.modose.app.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalE2eRunExporterTest {
    @Test
    fun export_createsSchemaShapedJson() {
        val result = PhysicalE2eRunExporter.export(validInput())

        assertTrue(result is E2eExportResult.Exported)
        val exported = (result as E2eExportResult.Exported).run
        val root = Json.parseToJsonElement(exported.json).jsonObject
        assertEquals("run-001.json", exported.fileName)
        assertEquals("run-001", root.getValue("runId").jsonPrimitive.content)
        assertEquals("A", root.getValue("objectSet").jsonPrimitive.content)
        assertEquals("wood", root.getValue("surface").jsonPrimitive.content)
        assertEquals(
            "verified",
            root.getValue("finalResult").jsonPrimitive.content,
        )
        assertEquals(
            2,
            root.getValue("positionErrorsCm").jsonArray.size,
        )
        assertFalse(exported.json.contains("sceneObjectId"))
        assertFalse(exported.json.contains("sha256"))
    }

    @Test
    fun export_writesNullForUncalledVerification() {
        val input = validInput().copy(
            execution = validInput().execution.copy(
                latencies = E2eApiLatencies(100, 200, null),
                matchResult = E2eMatchResult.Mixed,
                finalResult = E2eFinalResult.NeedsCorrection,
            ),
        )

        val result = PhysicalE2eRunExporter.export(input)
            as E2eExportResult.Exported
        val latencies = Json.parseToJsonElement(result.run.json)
            .jsonObject.getValue("latenciesMs").jsonObject

        assertEquals("null", latencies.getValue("verify").toString())
        assertTrue(result.run.json.contains("needs_correction"))
    }

    @Test
    fun export_rejectsVerifiedResultWithUnavailablePosition() {
        val input = validInput().copy(
            positions = E2ePositionErrorReport(
                samples = listOf(sample("wallet", 1.0)),
                unavailable = mapOf(
                    "keys" to E2ePositionUnavailableReason.Missing,
                ),
            ),
        )

        assertEquals(
            E2eExportResult.Rejected(
                E2eExportFailure.InconsistentVerifiedResult,
            ),
            PhysicalE2eRunExporter.export(input),
        )
    }

    @Test
    fun export_rejectsSupportedResultWithObjectCountMismatch() {
        val input = validInput().copy(
            positions = E2ePositionErrorReport(
                samples = listOf(sample("wallet", 1.0)),
                unavailable = emptyMap(),
            ),
        )

        assertEquals(
            E2eExportResult.Rejected(
                E2eExportFailure.InconsistentObjectCount,
            ),
            PhysicalE2eRunExporter.export(input),
        )
    }

    @Test
    fun export_allowsRejectedBoundaryRunWithoutPositionSamples() {
        val input = validInput().copy(
            execution = validInput().execution.copy(
                metadata = validInput().execution.metadata.copy(
                    objectCount = 6,
                    testCaseId = "T17",
                ),
                latencies = E2eApiLatencies(null, null, null),
                vlmCallCount = 0,
                matchResult = E2eMatchResult.NotRun,
                finalResult = E2eFinalResult.Rejected,
            ),
            positions = E2ePositionErrorReport(emptyList(), emptyMap()),
        )

        assertTrue(
            PhysicalE2eRunExporter.export(input) is
                E2eExportResult.Exported,
        )
    }

    @Test
    fun export_rejectsInvalidTimestampAndVideoLink() {
        val invalidTimestamp = validInput().copy(
            recordedAtUtc = "2026/09/15 12:00",
        )
        val invalidVideo = validInput().copy(
            video = video().copy(fileName = "another-run.mp4"),
        )

        assertEquals(
            E2eExportResult.Rejected(E2eExportFailure.InvalidTimestamp),
            PhysicalE2eRunExporter.export(invalidTimestamp),
        )
        assertEquals(
            E2eExportResult.Rejected(
                E2eExportFailure.InvalidVideoEvidence,
            ),
            PhysicalE2eRunExporter.export(invalidVideo),
        )
    }

    private fun validInput() = E2eExportInput(
        recordedAtUtc = "2026-09-15T03:00:00Z",
        objectSet = E2eObjectSet.A,
        surface = E2eSurface.Wood,
        execution = E2eExecutionRecord(
            metadata = E2eRunMetadata(
                runId = "run-001",
                sceneId = "scene-001",
                appVersion = "1.0.0",
                deviceModel = "Pixel 8",
                modelId = "gemini-3.5-flash",
                promptVersion = "baseline-v3",
                testCaseId = "T01",
                objectCount = 2,
            ),
            latencies = E2eApiLatencies(100, 200, 300),
            vlmCallCount = 3,
            matchResult = E2eMatchResult.Matched,
            finalResult = E2eFinalResult.Verified,
            falseSuccess = false,
        ),
        positions = E2ePositionErrorReport(
            samples = listOf(
                sample("wallet", 1.2),
                sample("keys", 2.4),
            ),
            unavailable = emptyMap(),
        ),
        video = video(),
    )

    private fun sample(id: String, error: Double) =
        E2ePositionErrorSample(id, error)

    private fun video() = E2eVideoEvidence(
        fileName = "run-001.mp4",
        sizeBytes = 1_024,
        sha256 = "a".repeat(64),
        reusedExisting = false,
    )
}
