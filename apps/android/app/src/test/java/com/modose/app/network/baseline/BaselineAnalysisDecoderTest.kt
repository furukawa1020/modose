package com.modose.app.network.baseline

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineAnalysisDecoderTest {
    @Test
    fun decodesContractCompliantAnalysis() {
        val result = BaselineAnalysisDecoder.decode(validJson().toByteArray()) as
            BaselineDecodeResult.Decoded

        assertEquals("gemini-test", result.analysis.modelId)
        assertEquals(false, result.analysis.repaired)
        assertEquals(1, result.analysis.objects.size)
        assertEquals(ObjectSymmetry.Bilateral, result.analysis.objects.single().symmetry)
        assertEquals(
            NormalizedBoundingBox(100, 200, 600, 800),
            result.analysis.objects.single().boundingBox,
        )
        assertEquals(ExclusionReason.Reflective, result.analysis.excludedCandidates.single().reason)
    }

    @Test
    fun rejectsUnknownFieldsAndWrongContractConstants() {
        assertRejected(
            BaselineContractViolation.UnknownField,
            validJson().replace("\"repaired\":false", "\"repaired\":false,\"extra\":1"),
        )
        assertRejected(
            BaselineContractViolation.InvalidConstant,
            validJson().replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\""),
        )
        assertRejected(
            BaselineContractViolation.InvalidConstant,
            validJson().replace("\"symmetry\":\"bilateral\"", "\"symmetry\":\"approximate\""),
        )
    }

    @Test
    fun rejectsInvertedBoundingBoxAndDuplicateIds() {
        assertRejected(
            BaselineContractViolation.InvalidBoundingBox,
            validJson().replace("\"yMax\":600", "\"yMax\":100"),
        )
        val duplicate = validJson().replace(
            "\"objects\":[",
            "\"objects\":[{\"id\":\"object-1\",\"displayName\":\"鍵\"," +
                "\"appearanceFeatures\":[\"銀色\"],\"boundingBox\":" +
                "{\"yMin\":10,\"xMin\":10,\"yMax\":20,\"xMax\":20}," +
                "\"orientationImportant\":false,\"symmetry\":\"none\"},",
        )
        assertRejected(BaselineContractViolation.DuplicateObjectId, duplicate)
    }

    @Test
    fun rejectsMalformedUtf8AndMissingObjects() {
        assertEquals(
            BaselineContractViolation.InvalidUtf8,
            (BaselineAnalysisDecoder.decode(byteArrayOf(0xC3.toByte(), 0x28)) as
                BaselineDecodeResult.Rejected).violation,
        )
        assertRejected(
            BaselineContractViolation.MissingField,
            validJson().replace(
                """          "objects":[{
            "id":"object-1",
            "displayName":"鍵",
            "appearanceFeatures":["銀色","黒い持ち手"],
            "boundingBox":{"yMin":100,"xMin":200,"yMax":600,"xMax":800},
            "orientationImportant":true,
            "symmetry":"bilateral"
          }],
""",
                "",
            ),
        )
    }

    private fun assertRejected(
        expected: BaselineContractViolation,
        json: String,
    ) {
        val result = BaselineAnalysisDecoder.decode(json.toByteArray())
        assertEquals(expected, (result as BaselineDecodeResult.Rejected).violation)
    }

    private fun validJson() =
        """
        {
          "schemaVersion":"1.0",
          "status":"ok",
          "modelId":"gemini-test",
          "promptVersion":"baseline-v1",
          "repaired":false,
          "objects":[{
            "id":"object-1",
            "displayName":"鍵",
            "appearanceFeatures":["銀色","黒い持ち手"],
            "boundingBox":{"yMin":100,"xMin":200,"yMax":600,"xMax":800},
            "orientationImportant":true,
            "symmetry":"bilateral"
          }],
          "excludedCandidates":[{
            "displayName":"鏡",
            "reason":"reflective"
          }]
        }
        """.trimIndent()
}
