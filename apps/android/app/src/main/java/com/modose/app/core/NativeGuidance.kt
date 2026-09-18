package com.modose.app.core

/** Positions are meters in the saved table plane, not screen or world pixels. */
internal data class NativeTablePosition(val x: Double, val z: Double)

internal enum class NativeRecoveryReason { UNOBSERVED, TRACKING_LOST, MISSING, AMBIGUOUS }

internal sealed interface NativeGuidance {
    val objectId: Int

    data class Move(
        override val objectId: Int,
        val from: NativeTablePosition,
        val to: NativeTablePosition,
        val distanceMeters: Double,
    ) : NativeGuidance

    data class Ring(
        override val objectId: Int,
        val target: NativeTablePosition,
    ) : NativeGuidance

    /** Requests orientation inspection; no unsupported angle is invented. */
    data class CheckOrientation(
        override val objectId: Int,
        val target: NativeTablePosition,
    ) : NativeGuidance

    /** No position is available for an arrow. */
    data class Recover(
        override val objectId: Int,
        val reason: NativeRecoveryReason,
    ) : NativeGuidance
}

/** Decode wire v1 strictly. Null means no local action, never verified success. */
internal fun decodeNativeGuidance(values: DoubleArray): NativeGuidance? {
    check(values.size in 3..8 && values.all { it.isFinite() }) { "Invalid native guidance payload" }
    check(values[0] == 1.0) { "Unsupported native guidance version" }
    if (values[2] == 0.0) {
        check(values.size == 3 && values[1] == 0.0) { "Invalid empty guidance" }
        return null
    }
    val id = values[1]
    check(id >= 1.0 && id <= Int.MAX_VALUE.toDouble() && id == id.toInt().toDouble()) {
        "Invalid guidance object ID"
    }
    fun target() = NativeTablePosition(values[3], values[4])
    return when (values[2]) {
        1.0 -> {
            check(values.size == 8 && values[7] >= 0.0) { "Invalid move guidance" }
            NativeGuidance.Move(
                id.toInt(), target(), NativeTablePosition(values[5], values[6]), values[7],
            )
        }
        2.0 -> {
            check(values.size == 5) { "Invalid ring guidance" }
            NativeGuidance.Ring(id.toInt(), target())
        }
        3.0 -> {
            check(values.size == 5) { "Invalid orientation guidance" }
            NativeGuidance.CheckOrientation(id.toInt(), target())
        }
        4.0 -> {
            check(values.size == 4) { "Invalid recovery guidance" }
            val reason = when (values[3]) {
                0.0 -> NativeRecoveryReason.UNOBSERVED
                1.0 -> NativeRecoveryReason.TRACKING_LOST
                2.0 -> NativeRecoveryReason.MISSING
                3.0 -> NativeRecoveryReason.AMBIGUOUS
                else -> throw IllegalStateException("Unknown recovery reason")
            }
            NativeGuidance.Recover(id.toInt(), reason)
        }
        else -> throw IllegalStateException("Unknown native guidance action")
    }
}
