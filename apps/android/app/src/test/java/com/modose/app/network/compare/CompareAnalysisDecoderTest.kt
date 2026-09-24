package com.modose.app.network.compare

import com.modose.app.flow.compare.CurrentObjectState
import com.modose.app.network.baseline.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompareAnalysisDecoderTest {
    private val box = """{"yMin":100,"xMin":200,"yMax":400,"xMax":500}"""
    private fun response(state: String = "moved", currentBox: String = box, reason: String = "") =
        """{"schemaVersion":"1.0","status":"ok","modelId":"fixture-model","promptVersion":"compare-v1","repaired":false,"matches":[{"baselineObjectId":"wallet","state":"$state","confidence":0.9,"currentBox":$currentBox,"ambiguityReason":"$reason"}],"addedObjects":[]}"""
    private fun decode(value: String) = CompareAnalysisDecoder.decode(value.toByteArray(), listOf("wallet"))

    @Test fun actualApiFieldsDecodeWithoutInventingAngles() {
        for (state in listOf("aligned", "moved", "rotated", "moved_rotated")) {
            assertNotNull(decode(response(state)))
        }
        assertEquals(CurrentObjectState.Moved, decode(response())!!.matches.single().state)
    }
    @Test fun missingAndAmbiguousRemainNonPositionedStates() {
        assertNull(decode(response("missing")))
        assertEquals(CurrentObjectState.Missing, decode(response("missing", "null"))!!.matches.single().state)
        assertNull(decode(response("ambiguous", "null")))
        assertEquals(CurrentObjectState.Ambiguous,
            decode(response("ambiguous", "null", "same appearance"))!!.matches.single().state)
    }
    @Test fun occlusionNeedsNoInventedOccludingId() {
        assertEquals(CurrentObjectState.Occluded, decode(response("occluded", "null"))!!.matches.single().state)
    }
    @Test fun unknownFieldsVersionsAndStatesAreRejected() {
        for (bad in listOf(response().replace("compare-v1", "compare-v2"),
            response().replace("\"status\":\"ok\"", "\"status\":\"error\""),
            response().replace("\"repaired\":false", "\"repaired\":false,\"extra\":1"),
            response("unknown"))) assertNull(decode(bad))
    }
    @Test fun numbersMustBeTypedFiniteAndInRange() {
        for (bad in listOf("\"0.9\"", "1.1", "-0.1", "1e999")) {
            assertNull(decode(response().replace("0.9", bad)))
        }
        assertNull(decode(response(currentBox = box.replace("100", "\"100\""))))
        assertNull(decode(response(currentBox = box.replace("400", "100"))))
    }
    @Test fun matchSetMustExactlyCoverSavedIds() {
        assertNull(CompareAnalysisDecoder.decode(response().toByteArray(), listOf("other")))
        assertNull(CompareAnalysisDecoder.decode(response().toByteArray(), listOf("wallet", "other")))
        assertNull(CompareAnalysisDecoder.decode(response().toByteArray(), listOf("wallet", "wallet")))
        val root = Json.parseToJsonElement(response()).jsonObject
        val item = root.getValue("matches").jsonArray.single()
        assertNull(decode(JsonObject(root + ("matches" to JsonArray(listOf(item, item)))).toString()))
    }
    @Test fun malformedUtf8OversizeAndDeepJsonAreRejected() {
        assertNull(CompareAnalysisDecoder.decode(byteArrayOf(0xc3.toByte(), 0x28), listOf("wallet")))
        assertNull(CompareAnalysisDecoder.decode(ByteArray(64_001), listOf("wallet")))
        assertNull(decode("[".repeat(1000) + "]".repeat(1000)))
        assertNull(decode(response() + "{}"))
    }
    @Test fun addedObjectsAreValidatedAndPreserved() {
        val root = Json.parseToJsonElement(response()).jsonObject
        val added = Json.parseToJsonElement("""{"displayName":"pen","currentBox":$box,"confidence":0.8}""")
        fun withAdded(count: Int) = JsonObject(root + ("addedObjects" to JsonArray(List(count) { added }))).toString()
        assertEquals(20, decode(withAdded(20))!!.addedObjects.size)
        assertNull(decode(withAdded(21)))
    }
    @Test fun confirmedPayloadEscapesNamesAndPreservesEdits() {
        val item = BaselineObject("wallet", "財布 \"確認済み\"", listOf("黒"), NormalizedBoundingBox(1, 2, 30, 40),
            true, ObjectSymmetry.Bilateral)
        val encoded = Json.parseToJsonElement(ConfirmedObjectsEncoder.encode(listOf(item))!!).jsonObject
        assertEquals(setOf("objects", "excludedCandidates"), encoded.keys)
        assertEquals(item.displayName, encoded.getValue("objects").jsonArray.single().jsonObject
            .getValue("displayName").jsonPrimitive.content)
        assertNull(ConfirmedObjectsEncoder.encode(listOf(item, item)))
        assertNull(ConfirmedObjectsEncoder.encode(listOf(item.copy(appearanceFeatures = emptyList()))))
    }
}
