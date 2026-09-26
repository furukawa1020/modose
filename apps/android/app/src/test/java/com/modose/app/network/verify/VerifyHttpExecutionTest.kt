package com.modose.app.network.verify

import com.modose.app.ar.image.VlmJpegImage
import com.modose.app.flow.verification.*
import com.modose.app.network.VisionApiResult
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VerifyHttpExecutionTest {
    private fun json(status: String = "verified", corrections: String = "[]", reason: String = "") =
        """{"schemaVersion":"1.0","status":"ok","modelId":"model","promptVersion":"verify-v1","repaired":false,"verificationStatus":"$status","corrections":$corrections,"uncertaintyReason":"$reason"}"""
    private fun input(ids: Set<String> = setOf("cup")) = ExecuteVerificationInput(
        "018f0f90-1234-4abc-8def-123456789abc", Instant.parse("2026-09-26T00:00:00Z"),
        "018f0f90-1234-7abc-8def-123456789abc",
        VlmJpegImage(byteArrayOf(1), 10, 10), VlmJpegImage(byteArrayOf(2), 10, 10),
        """{"objects":[],"excludedCandidates":[]}""", ids)
    private fun execute(body: String) = ExecuteFinalVerificationUseCase(
        executeRequest = { VisionApiResult.Success(200, body.toByteArray()) }).execute(input())

    @Test
    fun defaultDecoderAcceptsOnlyUncontradictedVerifiedPayload() {
        for (empty in listOf("[]", "null")) {
            val result = execute(json(corrections = empty)) as ExecuteVerificationResult.Completed
            assertEquals(SceneVerificationStatus.Verified, result.decision.status)
        }
        assertTrue(execute(json(reason = "not sure")) is ExecuteVerificationResult.Failed)
        assertTrue(execute(json(corrections = """[{"baselineObjectId":"cup","reason":"position"}]"""))
            is ExecuteVerificationResult.Failed)
    }

    @Test
    fun actualCorrectionAndUncertainPayloadsRemainNonSuccessful() {
        val correction = execute(json("needs_correction",
            """[{"baselineObjectId":"cup","reason":"position"}]""")) as ExecuteVerificationResult.Completed
        assertEquals(SceneVerificationStatus.NeedsCorrection, correction.decision.status)
        assertEquals(listOf("cup"), correction.decision.correctionObjectIds)
        val uncertain = execute(json("uncertain", reason = "occlusion")) as ExecuteVerificationResult.Completed
        assertEquals(SceneVerificationStatus.Uncertain, uncertain.decision.status)
    }

    @Test
    fun unknownDuplicateAndEmptyCorrectionsAreRejected() {
        for (corrections in listOf("[]", "null",
            """[{"baselineObjectId":"other","reason":"position"}]""",
            """[{"baselineObjectId":"cup","reason":"position"},{"baselineObjectId":"cup","reason":"angle"}]""")) {
            assertTrue(execute(json("needs_correction", corrections)) is ExecuteVerificationResult.Failed)
        }
    }

    @Test
    fun malformedUtf8OversizedDeepAndFreeTextBodiesAreRejected() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(64_001),
            "restored".toByteArray(), ("[".repeat(9) + "0" + "]".repeat(9)).toByteArray())) {
            assertTrue(VerifyAnalysisDecoder.decode(bytes) is VerificationDecodeResult.Rejected)
        }
    }

    @Test
    fun unknownMissingAndWrongTypeEnvelopeFieldsAreRejected() {
        for (body in listOf(json().replace("\"repaired\":false,", ""),
            json().replace("\"repaired\":false", "\"repaired\":\"false\""),
            json().replace("\"modelId\":\"model\"", "\"modelId\":123"),
            json().dropLast(1) + ",\"extra\":true}",
            json(corrections = "{}"))) {
            assertTrue(execute(body) is ExecuteVerificationResult.Failed)
        }
    }

    @Test
    fun schemaPromptStatusAndVerdictMustMatchContract() {
        for (body in listOf(json().replace("verify-v1", "verify-v2"),
            json().replace("\"1.0\"", "\"2.0\""),
            json().replace("\"ok\"", "\"error\""), json("success"))) {
            assertTrue(execute(body) is ExecuteVerificationResult.Failed)
        }
    }

    @Test
    fun invalidExpectedObjectsNeverInvokeTransport() {
        var calls = 0
        val useCase = ExecuteFinalVerificationUseCase(executeRequest = {
            calls++
            VisionApiResult.Success(200, json().toByteArray())
        })
        for (ids in listOf(emptySet(), setOf(" "), (1..6).map { "$it" }.toSet())) {
            assertTrue(useCase.execute(input(ids)) is ExecuteVerificationResult.Failed)
        }
        assertEquals(0, calls)
    }

    @Test
    fun callerMutationDuringTransportCannotAuthorizeAnotherObject() {
        val ids = mutableSetOf("cup")
        val useCase = ExecuteFinalVerificationUseCase(executeRequest = {
            ids.clear()
            ids.add("other")
            VisionApiResult.Success(200, json("needs_correction",
                """[{"baselineObjectId":"other","reason":"position"}]""").toByteArray())
        })
        assertTrue(useCase.execute(input(ids)) is ExecuteVerificationResult.Failed)
    }

    @Test
    fun networkFailureCannotBecomeVerified() {
        val result = ExecuteFinalVerificationUseCase(
            executeRequest = { VisionApiResult.NetworkFailure }).execute(input())
        assertEquals(ExecuteVerificationResult.Failed(ExecuteVerificationFailure.NetworkUnavailable), result)
    }
}
