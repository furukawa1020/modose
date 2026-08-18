package com.modose.app.ar.session

enum class ArSessionPhase {
    Empty,
    Created,
    Resumed,
    Paused,
    Closed,
}

enum class ArSessionCommand {
    Create,
    Resume,
    Pause,
    Close,
}

enum class ArSessionFailureReason {
    Closed,
    InvalidTransition,
    PermissionMissing,
    ArCoreNotInstalled,
    ArCoreApkTooOld,
    ArCoreSdkTooOld,
    DeviceNotCompatible,
    CameraUnavailable,
    Unknown,
}

sealed interface ArSessionResult {
    val phase: ArSessionPhase

    data class Applied(override val phase: ArSessionPhase) : ArSessionResult

    data class Rejected(
        override val phase: ArSessionPhase,
        val reason: ArSessionFailureReason,
    ) : ArSessionResult
}

interface ArSessionLifecycle {
    val phase: ArSessionPhase

    fun create(): ArSessionResult

    fun resume(): ArSessionResult

    fun pause(): ArSessionResult

    fun close(): ArSessionResult
}

object ArSessionTransitionPolicy {
    fun apply(
        phase: ArSessionPhase,
        command: ArSessionCommand,
    ): ArSessionResult = when {
        command == ArSessionCommand.Close -> ArSessionResult.Applied(ArSessionPhase.Closed)
        phase == ArSessionPhase.Closed -> ArSessionResult.Rejected(
            phase = phase,
            reason = ArSessionFailureReason.Closed,
        )
        command == ArSessionCommand.Create && phase == ArSessionPhase.Empty -> {
            ArSessionResult.Applied(ArSessionPhase.Created)
        }
        command == ArSessionCommand.Create && phase != ArSessionPhase.Empty -> {
            ArSessionResult.Applied(phase)
        }
        command == ArSessionCommand.Resume && phase == ArSessionPhase.Created -> {
            ArSessionResult.Applied(ArSessionPhase.Resumed)
        }
        command == ArSessionCommand.Resume && phase == ArSessionPhase.Paused -> {
            ArSessionResult.Applied(ArSessionPhase.Resumed)
        }
        command == ArSessionCommand.Resume && phase == ArSessionPhase.Resumed -> {
            ArSessionResult.Applied(phase)
        }
        command == ArSessionCommand.Pause && phase == ArSessionPhase.Resumed -> {
            ArSessionResult.Applied(ArSessionPhase.Paused)
        }
        command == ArSessionCommand.Pause -> ArSessionResult.Applied(phase)
        else -> ArSessionResult.Rejected(
            phase = phase,
            reason = ArSessionFailureReason.InvalidTransition,
        )
    }
}
