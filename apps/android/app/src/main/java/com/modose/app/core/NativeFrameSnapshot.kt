package com.modose.app.core

internal enum class NativeFrameHiddenReason {
    INVALID_INPUT, TRACKING_LOST, INVALID_CLOCK, STALE_FRAME, SUPERSEDED,
}

internal sealed interface NativeFramePresentation {
    /** Only Rust can produce the state. A null guidance is not verified success. */
    data class Ready(
        val state: CoreRestoreState,
        val guidance: NativeGuidance?,
    ) : NativeFramePresentation

    /** Deliberately contains neither an old arrow nor a successful state. */
    data class Hidden(
        val reason: NativeFrameHiddenReason,
        val encodingError: FrameEncodingError? = null,
    ) : NativeFramePresentation
}

/**
 * A revocable frame result. Renderers must call presentationAt for each draw,
 * using the same monotonic millisecond clock as the observation.
 * Never cache the returned Ready separately from this snapshot.
 */
internal class NativeFrameSnapshot internal constructor(
    private val observedAtMs: Long,
    private val presentation: NativeFramePresentation,
) {
    @Volatile
    private var active = true

    init {
        require(observedAtMs >= 0) { "Invalid snapshot timestamp" }
    }

    fun presentationAt(nowMs: Long): NativeFramePresentation = when {
        !active -> NativeFramePresentation.Hidden(NativeFrameHiddenReason.SUPERSEDED)
        nowMs < observedAtMs ->
            NativeFramePresentation.Hidden(NativeFrameHiddenReason.INVALID_CLOCK)
        nowMs - observedAtMs > MAX_FRAME_AGE_MS ->
            NativeFramePresentation.Hidden(NativeFrameHiddenReason.STALE_FRAME)
        else -> presentation
    }

    internal fun invalidate() {
        active = false
    }

    companion object {
        // Mirrors scene-core MAX_OBSERVATION_GAP_MS, not completion stability.
        private const val MAX_FRAME_AGE_MS = 200L
    }
}
