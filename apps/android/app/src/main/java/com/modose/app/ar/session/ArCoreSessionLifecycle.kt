package com.modose.app.ar.session

import android.content.Context
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException

internal interface ArSessionRuntime {
    fun resume()

    fun pause()

    fun close()
}

private class AndroidArSessionRuntime(
    private val session: Session,
) : ArSessionRuntime {
    override fun resume() = session.resume()

    override fun pause() = session.pause()

    override fun close() = session.close()
}

class ArCoreSessionLifecycle internal constructor(
    private val runtimeFactory: () -> ArSessionRuntime,
) : ArSessionLifecycle {
    constructor(context: Context) : this(
        runtimeFactory = { AndroidArSessionRuntime(Session(context.applicationContext)) },
    )

    override var phase: ArSessionPhase = ArSessionPhase.Empty
        private set

    private var runtime: ArSessionRuntime? = null

    override fun create(): ArSessionResult {
        val transition = ArSessionTransitionPolicy.apply(phase, ArSessionCommand.Create)
        if (transition is ArSessionResult.Rejected || phase != ArSessionPhase.Empty) {
            return transition
        }

        return try {
            val createdRuntime = runtimeFactory()
            runtime = createdRuntime
            phase = ArSessionPhase.Created
            ArSessionResult.Applied(phase)
        } catch (error: Exception) {
            ArSessionResult.Rejected(phase, error.toFailureReason())
        }
    }

    override fun resume(): ArSessionResult = withRuntimeCommand(ArSessionCommand.Resume) {
        resume()
    }

    override fun pause(): ArSessionResult = withRuntimeCommand(ArSessionCommand.Pause) {
        pause()
    }

    override fun close(): ArSessionResult {
        if (phase == ArSessionPhase.Closed) {
            return ArSessionResult.Applied(phase)
        }

        val ownedRuntime = runtime
        runtime = null
        return try {
            ownedRuntime?.close()
            phase = ArSessionPhase.Closed
            ArSessionResult.Applied(phase)
        } catch (_: Exception) {
            phase = ArSessionPhase.Closed
            ArSessionResult.Rejected(phase, ArSessionFailureReason.Unknown)
        }
    }

    private fun withRuntimeCommand(
        command: ArSessionCommand,
        operation: ArSessionRuntime.() -> Unit,
    ): ArSessionResult {
        val transition = ArSessionTransitionPolicy.apply(phase, command)
        if (transition is ArSessionResult.Rejected) {
            return transition
        }

        val ownedRuntime = runtime ?: return transition
        if (transition.phase == phase) {
            return transition
        }

        return try {
            ownedRuntime.operation()
            phase = transition.phase
            ArSessionResult.Applied(phase)
        } catch (error: Exception) {
            ArSessionResult.Rejected(phase, error.toFailureReason())
        }
    }
}

private fun Exception.toFailureReason(): ArSessionFailureReason = when (this) {
    is SecurityException -> ArSessionFailureReason.PermissionMissing
    is UnavailableArcoreNotInstalledException -> ArSessionFailureReason.ArCoreNotInstalled
    is UnavailableApkTooOldException -> ArSessionFailureReason.ArCoreApkTooOld
    is UnavailableSdkTooOldException -> ArSessionFailureReason.ArCoreSdkTooOld
    is UnavailableDeviceNotCompatibleException -> ArSessionFailureReason.DeviceNotCompatible
    is CameraNotAvailableException -> ArSessionFailureReason.CameraUnavailable
    is UnavailableException -> ArSessionFailureReason.Unknown
    else -> ArSessionFailureReason.Unknown
}
