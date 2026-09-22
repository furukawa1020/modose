package com.modose.app.ar.render

import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.ar.session.ArCameraFrame
import com.modose.app.ar.session.ArTrackingPhase
import com.modose.app.ar.image.CpuImageAcquisitionResult

internal data class BaselineCameraFrame(
    val frame: ArCameraFrame,
    val previousTimestampNanos: Long,
    val previousViewMatrix: FloatArray?,
    val validity: BaselineCaptureValidity? = null,
)

/** One outstanding user request. No image is retained between requests. */
internal class BaselineCaptureMailbox {
    private var pending: ((BaselineCameraFrame?) -> Unit)? = null
    private var validity: BaselineCaptureValidity? = null

    @Synchronized
    fun observe(frame: ArCameraFrame) {
        val anchorId = if (frame.trackingDiagnostics.phase == ArTrackingPhase.Tracking) {
            (frame.sceneAnchorState as? SceneAnchorState.Tracking)?.anchor?.id
        } else null
        if (anchorId != validity?.anchorId) {
            validity?.invalidate()
            validity = anchorId?.let { BaselineCaptureValidity(it) }
        }
    }

    private var previousTimestamp = 0L
    private var previousView: FloatArray? = null

    @Synchronized
    fun request(consumer: (BaselineCameraFrame?) -> Unit): Boolean {
        if (pending != null) return false
        pending = consumer
        return true
    }

    fun offer(frame: ArCameraFrame) {
        var consumer: ((BaselineCameraFrame?) -> Unit)? = null
        var packet: BaselineCameraFrame? = null
        synchronized(this) {
            observe(frame)
            val acquired = frame.cpuImageResult is CpuImageAcquisitionResult.Acquired
            if (pending != null && acquired) {
                consumer = pending
                pending = null
                packet = BaselineCameraFrame(frame, previousTimestamp, previousView?.copyOf(), validity)
            }
            if (frame.trackingDiagnostics.phase == ArTrackingPhase.Tracking &&
                frame.timestampNanos > previousTimestamp
            ) {
                previousTimestamp = frame.timestampNanos
                previousView = frame.viewMatrix?.copyOf()
            } else if (frame.trackingDiagnostics.phase != ArTrackingPhase.Tracking) {
                previousTimestamp = 0L
                previousView = null
            }
        }
        consumer?.invoke(packet)
    }

    fun cancel() {
        val consumer = synchronized(this) {
            validity?.invalidate()
            validity = null
            val owned = pending
            pending = null
            previousTimestamp = 0L
            previousView = null
            owned
        }
        consumer?.invoke(null)
    }
}
