package com.modose.app.core

/** Valid only for the owning live session and one pending native confirmation. */
internal data class NativeVerificationTicket(val owner: Long, val token: Long)

internal enum class CoreVerificationVerdict(val wireCode: Int) {
    VERIFIED(0),
    NEEDS_CORRECTION(1),
    UNCERTAIN(2),
}

internal data class NativeObjectVerdict(
    val savedId: Int,
    val verdict: CoreVerificationVerdict,
)

/** Transport failure is not a semantic verdict and must consume a native attempt. */
internal sealed interface NativeVerificationResult {
    data object Unavailable : NativeVerificationResult

    data class Analyzed(
        val overall: CoreVerificationVerdict,
        val objects: List<NativeObjectVerdict>,
    ) : NativeVerificationResult
}
