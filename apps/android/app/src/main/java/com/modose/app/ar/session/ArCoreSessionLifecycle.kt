package com.modose.app.ar.session

import android.content.Context
import com.google.ar.core.Session
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.modose.app.ar.plane.HorizontalPlaneCandidate
import com.modose.app.ar.plane.HorizontalPlaneSelectionPolicy
import com.modose.app.ar.plane.HorizontalPlaneState
import com.modose.app.ar.plane.HorizontalPlaneStateTracker
import com.modose.app.ar.plane.SelectedHorizontalPlane
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

    fun bindCameraTexture(textureId: Int) = Unit

    fun updateCameraFrame(
        displayRotation: Int,
        widthPx: Int,
        heightPx: Int,
    ): ArCameraFrame = error("Camera frame updates are not available")
}

private class AndroidArSessionRuntime(
    private val session: Session,
) : ArSessionRuntime {
    private val planeStateTracker = HorizontalPlaneStateTracker()
    private var selectedPlane: Plane? = null
    private var selectedPlaneDistanceMeters = 0f
    override fun resume() = session.resume()

    override fun pause() = session.pause()

    override fun close() {
        selectedPlane = null
        planeStateTracker.reset()
        session.close()
    }

    override fun bindCameraTexture(textureId: Int) {
        session.setCameraTextureName(textureId)
    }

    override fun updateCameraFrame(
        displayRotation: Int,
        widthPx: Int,
        heightPx: Int,
    ): ArCameraFrame {
        session.setDisplayGeometry(displayRotation, widthPx, heightPx)
        val frame = session.update()
        val transformedCoordinates = if (frame.hasDisplayGeometryChanged()) {
            FloatArray(OPEN_GL_QUAD.size).also { output ->
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    OPEN_GL_QUAD,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    output,
                )
            }
        } else {
            null
        }
        return ArCameraFrame(
            timestampNanos = frame.timestamp,
            transformedTextureCoordinates = transformedCoordinates,
            trackingDiagnostics = ArTrackingDiagnosticsPolicy.resolve(
                trackingState = frame.camera.trackingState,
                failureReason = frame.camera.trackingFailureReason,
            ),
            horizontalPlaneState = updateHorizontalPlaneState(frame, widthPx, heightPx),
        )
    }

    private fun updateHorizontalPlaneState(
        frame: com.google.ar.core.Frame,
        widthPx: Int,
        heightPx: Int,
    ): HorizontalPlaneState {
        val currentPlane = selectedPlane
        if (currentPlane != null && currentPlane.isSelectable()) {
            return planeStateTracker.update(
                currentPlane.toSelectedPlane(selectedPlaneDistanceMeters),
            )
        }
        if (currentPlane != null) {
            selectedPlane = null
            return planeStateTracker.update(null)
        }
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return planeStateTracker.update(null)
        }

        val planeById = mutableMapOf<Long, Plane>()
        val selected = HorizontalPlaneSelectionPolicy.select(
            frame.hitTest(widthPx / 2f, heightPx / 2f).mapNotNull { hit ->
                val plane = hit.trackable as? Plane ?: return@mapNotNull null
                val id = System.identityHashCode(plane).toLong()
                planeById[id] = plane
                HorizontalPlaneCandidate(
                    id = id,
                    distanceMeters = hit.distance,
                    extentXMeters = plane.extentX,
                    extentZMeters = plane.extentZ,
                    isUpwardFacing = plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING,
                    isTracking = plane.trackingState == TrackingState.TRACKING,
                    isSubsumed = plane.subsumedBy != null,
                    containsCenterHit = plane.isPoseInPolygon(hit.hitPose),
                )
            },
        )
        if (selected != null) {
            selectedPlane = planeById[selected.id]
            selectedPlaneDistanceMeters = selected.distanceMeters
        }
        return planeStateTracker.update(selected)
    }

    private fun Plane.isSelectable(): Boolean =
        type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
            trackingState == TrackingState.TRACKING &&
            subsumedBy == null

    private fun Plane.toSelectedPlane(distanceMeters: Float) = SelectedHorizontalPlane(
        id = System.identityHashCode(this).toLong(),
        distanceMeters = distanceMeters,
        extentXMeters = extentX,
        extentZMeters = extentZ,
    )

    private companion object {
        val OPEN_GL_QUAD = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
    }
}

class ArCoreSessionLifecycle internal constructor(
    private val runtimeFactory: () -> ArSessionRuntime,
) : ArSessionLifecycle, ArCameraFrameSource {
    constructor(context: Context) : this(
        runtimeFactory = { AndroidArSessionRuntime(Session(context.applicationContext)) },
    )

    @Volatile
    override var phase: ArSessionPhase = ArSessionPhase.Empty
        private set

    @Volatile
    private var runtime: ArSessionRuntime? = null
    private var boundTextureId: Int? = null
    private var glThreadId: Long? = null

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
        boundTextureId = null
        glThreadId = null
        return try {
            ownedRuntime?.close()
            phase = ArSessionPhase.Closed
            ArSessionResult.Applied(phase)
        } catch (_: Exception) {
            phase = ArSessionPhase.Closed
            ArSessionResult.Rejected(phase, ArSessionFailureReason.Unknown)
        }
    }

    override fun bindCameraTexture(textureId: Int): ArCameraFrameResult {
        if (phase != ArSessionPhase.Resumed) {
            return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.SessionNotResumed)
        }
        if (textureId <= 0) {
            return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.TextureNotBound)
        }
        if (!claimOrCheckGlThread()) {
            return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.WrongGlThread)
        }
        if (boundTextureId == textureId) return ArCameraFrameResult.TextureBound

        return try {
            runtime?.bindCameraTexture(textureId)
                ?: return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.SessionNotResumed)
            boundTextureId = textureId
            ArCameraFrameResult.TextureBound
        } catch (_: Exception) {
            ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.Unknown)
        }
    }

    override fun updateCameraFrame(
        displayRotation: Int,
        widthPx: Int,
        heightPx: Int,
    ): ArCameraFrameResult {
        if (phase != ArSessionPhase.Resumed) {
            return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.SessionNotResumed)
        }
        if (boundTextureId == null) {
            return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.TextureNotBound)
        }
        if (widthPx <= 0 || heightPx <= 0) {
            return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.InvalidSurfaceSize)
        }
        if (!claimOrCheckGlThread()) {
            return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.WrongGlThread)
        }

        return try {
            ArCameraFrameResult.Updated(
                runtime?.updateCameraFrame(displayRotation, widthPx, heightPx)
                    ?: return ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.SessionNotResumed),
            )
        } catch (_: CameraNotAvailableException) {
            ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.CameraUnavailable)
        } catch (_: Exception) {
            ArCameraFrameResult.Rejected(ArCameraFrameFailureReason.Unknown)
        }
    }

    private fun claimOrCheckGlThread(): Boolean {
        val currentThreadId = Thread.currentThread().id
        val ownerThreadId = glThreadId
        if (ownerThreadId == null) glThreadId = currentThreadId
        return ownerThreadId == null || ownerThreadId == currentThreadId
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
