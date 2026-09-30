package com.modose.app.network.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class ConfirmedObjectsValidatorTest {
    @Test
    fun acceptsOneAndFiveObjectsAndPreservesTheirIds() {
        for (ids in listOf(listOf("cup"), (1..5).map { "object-$it" })) {
            val validated = requireNotNull(ConfirmedObjectsValidator.validate(
                ConfirmedObjectsFixture.json(*ids.toTypedArray()),
            ))
            assertEquals(ids.toSet(), validated.objectIds)
            assertNotNull(ConfirmedObjectsValidator.validate(validated.json))
        }
    }

    @Test
    fun rejectsMalformedWrongRootMissingAndUnknownFields() {
        for (text in listOf("", "[]", "{", "{broken}", "{}",
            """{"objects":[]}""",
            """{"objects":[],"excludedCandidates":[],"extra":1}""",
            """{"objects":[],"excludedCandidates":[]}""")) {
            assertNull(ConfirmedObjectsValidator.validate(text))
        }
    }

    @Test
    fun rejectsByteLimitAndDeepNestingBeforeRecursiveParsing() {
        assertNull(ConfirmedObjectsValidator.validate(" ".repeat(64_001)))
        assertNull(ConfirmedObjectsValidator.validate("[".repeat(9) + "0" + "]".repeat(9)))
        val manyBytes = ConfirmedObjectsFixture.json().replace("white", "\u3042".repeat(22_000))
        assertNull(ConfirmedObjectsValidator.validate(manyBytes))
    }

    @Test
    fun quotedBracketsDoNotCountAsNesting() {
        val text = ConfirmedObjectsFixture.json().replace(
            "white", "[[[[[[[[[white]]]]]]]]]",
        )
        assertNotNull(ConfirmedObjectsValidator.validate(text))
    }

    @Test
    fun rejectsDuplicateIdsAndSixObjects() {
        val objectJson = Json.parseToJsonElement(ConfirmedObjectsFixture.json()).jsonObject.getValue("objects").jsonArray.single().toString()
        for (count in listOf(2, 6)) {
            val objects = (1..count).joinToString(",") { objectJson }
            assertNull(ConfirmedObjectsValidator.validate(
                """{"objects":[$objects],"excludedCandidates":[]}""",
            ))
        }
    }

    @Test
    fun rejectsUnknownSymmetryAndWrongCoordinateTypes() {
        val valid = ConfirmedObjectsFixture.json()
        assertNull(ConfirmedObjectsValidator.validate(valid.replace("rotational", "unknown")))
        assertNull(ConfirmedObjectsValidator.validate(
            valid.replace("100", "\"100\""),
        ))
    }
}
