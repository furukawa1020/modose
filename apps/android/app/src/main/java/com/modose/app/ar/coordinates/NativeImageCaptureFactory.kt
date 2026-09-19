package com.modose.app.ar.coordinates

import android.opengl.Matrix
import com.modose.app.ar.image.CpuImageAcquisitionResult
import com.modose.app.ar.session.ArCameraFrame
import com.modose.app.ar.session.ArTrackingPhase
import com.modose.app.core.NativeGuidanceEpoch
import com.modose.app.core.NativeImageCapture

internal object NativeImageCaptureFactory {
    /** Invoke on the capture thread before starting asynchronous recognition. */
    fun capture(frame: ArCameraFrame, epoch: NativeGuidanceEpoch, observedAtMs: Long): NativeImageCapture? {
        if (frame.trackingDiagnostics.phase != ArTrackingPhase.Tracking || frame.timestampNanos <= 0) return null
        val image = (frame.cpuImageResult as? CpuImageAcquisitionResult.Acquired)?.image ?: return null
        val mapping = (frame.imageViewTransformResult as? ImageViewTransformFrameResult.Available)
            ?.snapshot ?: return null
        if (mapping.frameTimestampNanos != frame.timestampNanos ||
            image.timestampNanos != frame.timestampNanos ||
            image.widthPx != mapping.imageWidthPx || image.heightPx != mapping.imageHeightPx
        ) return null
        val view = frame.viewMatrix?.copyOf() ?: return null
        val projection = frame.projectionMatrix?.copyOf() ?: return null
        if (view.size != 16 || projection.size != 16 ||
            view.any { !it.isFinite() } || projection.any { !it.isFinite() }
        ) return null
        val combined = FloatArray(16)
        val inverse = FloatArray(16)
        Matrix.multiplyMM(combined, 0, projection, 0, view, 0)
        if (combined.any { !it.isFinite() } || !Matrix.invertM(inverse, 0, combined, 0)) return null
        fun mapped(x: Float, y: Float): ViewPixelPoint? =
            when (val result = mapping.transform.imageToView(ImagePixelPoint(x, y))) {
                is CoordinateTransformResult.Transformed -> result.value
                is CoordinateTransformResult.Rejected -> null
            }
        val origin = mapped(0f, 0f) ?: return null
        val xEnd = mapped(image.widthPx.toFloat(), 0f) ?: return null
        val yEnd = mapped(0f, image.heightPx.toFloat()) ?: return null
        val affine = doubleArrayOf(
            (xEnd.x.toDouble() - origin.x) / image.widthPx,
            (yEnd.x.toDouble() - origin.x) / image.heightPx,
            (xEnd.y.toDouble() - origin.y) / image.widthPx,
            (yEnd.y.toDouble() - origin.y) / image.heightPx,
            origin.x.toDouble(), origin.y.toDouble(),
        )
        return NativeImageCapture.create(epoch, observedAtMs, image.timestampNanos,
            image.widthPx, image.heightPx, mapping.viewWidthPx, mapping.viewHeightPx,
            affine, DoubleArray(16) { inverse[it].toDouble() })
    }
}
