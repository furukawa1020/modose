package com.modose.app.flow.verification

import com.modose.app.core.*
import org.junit.Assert.*
import org.junit.Test

class NativeVerificationDecisionMapperTest {
    private val mapper = NativeVerificationDecisionMapper(linkedMapOf("cup" to 7, "card" to 9))
    private fun decision(status: SceneVerificationStatus, corrections: List<String> = emptyList(),
        reasons: Set<String> = emptySet()) = ExecuteVerificationResult.Completed(
        SceneVerificationDecision(status, corrections, reasons))

    @Test
    fun verifiedMapsExactlyAllSavedObjects() {
        val result = mapper.map(decision(SceneVerificationStatus.Verified)) as NativeVerificationResult.Analyzed
        assertEquals(CoreVerificationVerdict.VERIFIED, result.overall)
        assertEquals(listOf(NativeObjectVerdict(7, CoreVerificationVerdict.VERIFIED),
            NativeObjectVerdict(9, CoreVerificationVerdict.VERIFIED)), result.objects)
    }

    @Test
    fun correctionsNeverInventVerificationForOmittedObjects() {
        val result = mapper.map(decision(SceneVerificationStatus.NeedsCorrection,
            listOf("card"), setOf("position"))) as NativeVerificationResult.Analyzed
        assertEquals(CoreVerificationVerdict.NEEDS_CORRECTION, result.overall)
        assertEquals(CoreVerificationVerdict.UNCERTAIN, result.objects.single { it.savedId == 7 }.verdict)
        assertEquals(CoreVerificationVerdict.NEEDS_CORRECTION, result.objects.single { it.savedId == 9 }.verdict)
    }

    @Test
    fun uncertainAndTransportFailureCannotBecomeVerified() {
        val result = mapper.map(decision(SceneVerificationStatus.Uncertain, reasons = setOf("occluded")))
            as NativeVerificationResult.Analyzed
        assertTrue(result.objects.all { it.verdict == CoreVerificationVerdict.UNCERTAIN })
        assertEquals(NativeVerificationResult.Unavailable,
            mapper.map(ExecuteVerificationResult.Failed(ExecuteVerificationFailure.TimedOut)))
    }

    @Test
    fun malformedSemanticVerdictsAreRejectedAgainAtNativeBoundary() {
        for (result in listOf(decision(SceneVerificationStatus.Verified, listOf("cup")),
            decision(SceneVerificationStatus.Verified, reasons = setOf("unknown")),
            decision(SceneVerificationStatus.NeedsCorrection, listOf("other"), setOf("position")),
            decision(SceneVerificationStatus.NeedsCorrection, listOf("cup", "cup"), setOf("position")),
            decision(SceneVerificationStatus.Uncertain))) {
            assertEquals(NativeVerificationResult.Unavailable, mapper.map(result))
        }
    }

    @Test
    fun identityMappingIsCopiedAndMustBeUnique() {
        val ids = mutableMapOf("cup" to 7)
        val owned = NativeVerificationDecisionMapper(ids)
        ids["cup"] = 99
        val result = owned.map(decision(SceneVerificationStatus.Verified)) as NativeVerificationResult.Analyzed
        assertEquals(7, result.objects.single().savedId)
        for (invalid in listOf(emptyMap(), mapOf("cup" to 0), mapOf("cup" to 7, "card" to 7))) {
            assertThrows(IllegalArgumentException::class.java) { NativeVerificationDecisionMapper(invalid) }
        }
    }
}
