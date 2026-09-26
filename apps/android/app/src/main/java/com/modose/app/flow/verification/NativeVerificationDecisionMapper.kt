package com.modose.app.flow.verification

import com.modose.app.core.*

/** Exact saved identity set; an omitted correction is not per-object verified evidence. */
internal class NativeVerificationDecisionMapper(objectIds: Map<String, Int>) {
    private val ids = objectIds.toMap()

    init {
        require(ids.size in 1..5 && ids.keys.all { it.isNotBlank() && it.length <= 64 })
        require(ids.values.all { it > 0 } && ids.values.toSet().size == ids.size)
    }

    fun map(result: ExecuteVerificationResult): NativeVerificationResult {
        val decision = (result as? ExecuteVerificationResult.Completed)?.decision
            ?: return NativeVerificationResult.Unavailable
        val corrections = decision.correctionObjectIds.toList()
        val reasons = decision.reasonCodes.toSet()
        val overall = when (decision.status) {
            SceneVerificationStatus.Verified -> {
                if (corrections.isNotEmpty() || reasons.isNotEmpty()) return NativeVerificationResult.Unavailable
                CoreVerificationVerdict.VERIFIED
            }
            SceneVerificationStatus.NeedsCorrection -> {
                if (corrections.isEmpty() || corrections.toSet().size != corrections.size ||
                    corrections.any { it !in ids } || reasons.isEmpty() || reasons.any { it.isBlank() }
                ) return NativeVerificationResult.Unavailable
                CoreVerificationVerdict.NEEDS_CORRECTION
            }
            SceneVerificationStatus.Uncertain -> {
                if (corrections.isNotEmpty() || reasons.isEmpty() || reasons.any { it.isBlank() }) {
                    return NativeVerificationResult.Unavailable
                }
                CoreVerificationVerdict.UNCERTAIN
            }
        }
        return NativeVerificationResult.Analyzed(overall, ids.map { (external, id) ->
            val verdict = when {
                overall == CoreVerificationVerdict.VERIFIED -> CoreVerificationVerdict.VERIFIED
                external in corrections -> CoreVerificationVerdict.NEEDS_CORRECTION
                else -> CoreVerificationVerdict.UNCERTAIN
            }
            NativeObjectVerdict(id, verdict)
        })
    }
}
