package com.modose.app.core

/** ABI names are fixed; these instance methods match the Rust JNI exports. */
internal object NativeSceneBindings {
    init {
        System.loadLibrary("scene_core_jni")
    }

    external fun nativeProjectTargets(geometry: DoubleArray, rays: DoubleArray): DoubleArray
    external fun nativeCreate(geometry: DoubleArray, targetIds: IntArray, targets: DoubleArray): Long
    external fun nativeApply(handle: Long, observedAtMs: Long, packet: ByteArray): Int
    external fun nativeBeginVerification(handle: Long, observedAtMs: Long): Long
    external fun nativeCompleteVerification(
        handle: Long, token: Long, observedAtMs: Long, overall: Int,
        objectIds: IntArray, verdicts: IntArray,
    ): Int
    external fun nativeGuidance(handle: Long, nowMs: Long): DoubleArray
    external fun nativeClose(handle: Long)
}

internal enum class CoreRestoreState {
    GUIDING,
    AWAITING_VERIFICATION,
    VERIFYING,
    VERIFIED,
    MANUAL_CONFIRMATION;

    companion object {
        fun fromNative(code: Int): CoreRestoreState = when (code) {
            0 -> GUIDING
            1 -> AWAITING_VERIFICATION
            2 -> VERIFYING
            3 -> VERIFIED
            4 -> MANUAL_CONFIRMATION
            else -> throw IllegalStateException("Unknown native restoration state")
        }
    }
}

/**
 * Owns one native handle. No automatic finalizer: the AR session owner must close
 * this object on reset/disposal. Calls are serialized with close; never cache an
 * old successful state after an exception.
 */
internal class NativeSceneSession private constructor(
    private var handle: Long,
    private val worldBasis: NativeWorldBasis,
) : AutoCloseable {
    private var latestFrame: NativeFrameSnapshot? = null

    private val dispatcher = CoreFrameDispatcher(CoreFrameTransport<CoreRestoreState> { owner, time, bytes ->
        check(owner == handle && handle > 0) { "Native session is closed" }
        CoreRestoreState.fromNative(NativeSceneBindings.nativeApply(owner, time, bytes))
    })

    @Synchronized
    fun update(
        observedAtMs: Long,
        trackingValid: Boolean,
        detections: List<CoreDetection>,
        evidence: List<CorePairEvidence>,
    ): FrameSubmission<CoreRestoreState> {
        invalidatePresentation()
        check(handle > 0) { "Native session is closed" }
        try {
            return dispatcher.submit(handle, observedAtMs, trackingValid, detections, evidence)
        } catch (failure: Throwable) {
            // Native rejection may already have destroyed the handle. Retire
            // local ownership first and preserve the original failure.
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /**
     * The same monitor protects apply, guidance, publication, verification and
     * close. Inputs must not be concurrently mutated by their producers.
     */
    @Synchronized
    fun updateGuidance(
        observedAtMs: Long,
        trackingValid: Boolean,
        detections: List<CoreDetection>,
        evidence: List<CorePairEvidence>,
    ): NativeFrameSnapshot {
        val applied = update(observedAtMs, trackingValid, detections, evidence)
        val presentation = when (applied) {
            is FrameSubmission.Invalidated -> NativeFramePresentation.Hidden(
                NativeFrameHiddenReason.INVALID_INPUT, applied.reason,
            )
            is FrameSubmission.Applied -> if (!trackingValid) {
                NativeFramePresentation.Hidden(NativeFrameHiddenReason.TRACKING_LOST)
            } else {
                NativeFramePresentation.Ready(applied.state, guidance(observedAtMs))
            }
        }
        return NativeFrameSnapshot(observedAtMs, presentation).also { latestFrame = it }
    }

    /** Same-session world geometry for a renderer; still subject to frame expiry. */
    @Synchronized
    fun updateWorldGuidance(
        observedAtMs: Long,
        trackingValid: Boolean,
        detections: List<CoreDetection>,
        evidence: List<CorePairEvidence>,
    ): NativeWorldFrameSnapshot = NativeWorldFrameSnapshot(
        updateGuidance(observedAtMs, trackingValid, detections, evidence), worldBasis,
    )

    private fun invalidatePresentation() {
        latestFrame?.invalidate()
        latestFrame = null
    }

    /**
     * Read one current action using the same monotonic clock as frame updates.
     * Do not cache an arrow across frames or treat null as verified completion.
     */
    @Synchronized
    fun guidance(nowMs: Long): NativeGuidance? = verificationCall {
        require(nowMs >= 0) { "Invalid guidance timestamp" }
        decodeNativeGuidance(NativeSceneBindings.nativeGuidance(handle, nowMs))
    }

    @Synchronized
    fun beginVerification(observedAtMs: Long): NativeVerificationTicket = verificationCall {
        invalidatePresentation()
        require(observedAtMs >= 0) { "Invalid verification timestamp" }
        val token = NativeSceneBindings.nativeBeginVerification(handle, observedAtMs)
        check(token > 0) { "Native verification returned an invalid token" }
        NativeVerificationTicket(handle, token)
    }

    @Synchronized
    fun completeVerification(
        ticket: NativeVerificationTicket,
        observedAtMs: Long,
        result: NativeVerificationResult,
    ): CoreRestoreState = verificationCall {
        invalidatePresentation()
        require(ticket.owner == handle && ticket.token > 0) { "Verification owner mismatch" }
        require(observedAtMs >= 0) { "Invalid verification timestamp" }
        val overall: Int
        val ids: IntArray
        val verdicts: IntArray
        when (result) {
            NativeVerificationResult.Unavailable -> {
                overall = 3
                ids = intArrayOf()
                verdicts = intArrayOf()
            }
            is NativeVerificationResult.Analyzed -> {
                require(result.objects.size in 1..5) { "Invalid verification object count" }
                val objects = result.objects.toList()
                overall = result.overall.wireCode
                ids = objects.map { it.savedId }.toIntArray()
                verdicts = objects.map { it.verdict.wireCode }.toIntArray()
            }
        }
        CoreRestoreState.fromNative(NativeSceneBindings.nativeCompleteVerification(
            handle, ticket.token, observedAtMs, overall, ids, verdicts,
        ))
    }

    private inline fun <T> verificationCall(block: () -> T): T {
        check(handle > 0) { "Native session is closed" }
        try {
            return block()
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        invalidatePresentation()
        val owned = handle
        if (owned == 0L) return
        handle = 0
        NativeSceneBindings.nativeClose(owned)
    }

    companion object {
        /**
         * geometry: origin xyz, x-axis xyz, z-axis xyz, 3..64 boundary x/z pairs.
         * targets: one x/z pair for each saved positive ID.
         * Inputs must not be concurrently mutated during creation.
         */
        fun create(geometry: DoubleArray, targetIds: IntArray, targets: DoubleArray): NativeSceneSession {
            require(geometry.size in 15..137 && (geometry.size - 9) % 2 == 0)
            require(targetIds.size in 1..5 && targets.size == targetIds.size * 2)
            val geometryCopy = geometry.copyOf()
            val worldBasis = NativeWorldBasis.fromGeometry(geometryCopy)
            val nativeHandle = NativeSceneBindings.nativeCreate(
                geometryCopy, targetIds.copyOf(), targets.copyOf(),
            )
            check(nativeHandle > 0) { "Native creation returned an invalid handle" }
            return NativeSceneSession(nativeHandle, worldBasis)
        }
    }
}
