package com.modose.app.ar.render

import android.content.Context
import android.os.Looper
import com.modose.app.ar.coordinates.NativeImageCaptureFactory
import com.modose.app.ar.image.CpuCameraImage
import com.modose.app.ar.image.CpuImageAcquisitionResult
import com.modose.app.core.NativeImageCapture
import com.modose.app.core.NativeGuidancePipeline
import com.modose.app.core.NativeGuidanceSink
import com.modose.app.core.NativeFrameDropReason
import com.modose.app.core.NativeFrameProcessing
import com.modose.app.vision.tracking.GuidanceImageEvidenceSource
import com.modose.app.vision.tracking.CpuImageDetectionSink
import com.modose.app.vision.tracking.CpuImageEvidenceSource
import com.modose.app.vision.tracking.ImageDetectionResult
import com.modose.app.vision.tracking.ImageGuidanceBridge
import com.modose.app.vision.tracking.MlKitImageDetector
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import com.modose.app.ar.anchor.SceneAnchorSnapshot
import com.modose.app.core.NativeGuideGeometry
import com.modose.app.core.NativeWorldFrameSnapshot
import com.modose.app.ar.session.ArCameraFrame
import com.modose.app.ar.session.ArTrackingPhase
import com.modose.app.ar.anchor.SceneAnchorState
import com.modose.app.ar.plane.HorizontalPlaneState
import com.modose.app.ar.session.ArCameraFrameFailureReason
import com.modose.app.ar.session.ArCameraFrameResult
import com.modose.app.ar.session.ArCameraFrameSource
import com.modose.app.ar.session.ArTrackingDiagnostics
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

sealed interface CameraBackgroundSurfaceFailure {
    data class Frame(val reason: ArCameraFrameFailureReason) : CameraBackgroundSurfaceFailure
    data class Renderer(
        val reason: CameraBackgroundFailureReason,
    ) : CameraBackgroundSurfaceFailure
}

interface CameraBackgroundSurfaceController {
    fun onActivityResume()

    fun onActivityPause()

    fun releaseSurface()
}

class CameraBackgroundSurfaceView(
    context: Context,
    private val onFailure: (CameraBackgroundSurfaceFailure) -> Unit,
    private val onTrackingDiagnostics: (ArTrackingDiagnostics?) -> Unit,
    private val onHorizontalPlaneState: (HorizontalPlaneState?) -> Unit,
    private val onSceneAnchorState: (SceneAnchorState?) -> Unit,
) : GLSurfaceView(context), CameraBackgroundSurfaceController {
    private val baselineCapture = BaselineCaptureMailbox()

    internal fun requestBaselineFrame(consumer: (BaselineCameraFrame?) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return !released && activityResumed && baselineCapture.request(consumer)
    }

    internal fun cancelBaselineCapture() = baselineCapture.cancel()

    private val cameraRenderer = CameraSurfaceRenderer(
        baselineCapture = baselineCapture,
        displayRotation = { display?.rotation ?: Surface.ROTATION_0 },
        onFailure = { failure -> post { onFailure(failure) } },
        onTrackingDiagnostics = { diagnostics -> post { onTrackingDiagnostics(diagnostics) } },
        onHorizontalPlaneState = { state -> post { onHorizontalPlaneState(state) } },
        onSceneAnchorState = { state -> post { onSceneAnchorState(state) } },
        onRecognitionFailure = { binding ->
            post {
                if (recognitionSession === binding) stopGuidance() else binding.close()
            }
        },
    )
    @Volatile
    private var activityResumed = false
    @Volatile
    private var released = false
    private var recognitionSession: RecognitionCaptureBinding? = null

    var frameSource: ArCameraFrameSource?
        get() = cameraRenderer.frameSource
        set(value) {
            if (cameraRenderer.frameSource !== value) {
                baselineCapture.cancel()
                stopGuidance()
            }
            cameraRenderer.frameSource = value
        }

    /** Called by the scene owner on UI after saving geometry/targets and preparing evidence. */
    internal fun startGuidance(
        sceneId: String,
        source: ArCameraFrameSource,
        anchor: SceneAnchorSnapshot,
        geometry: DoubleArray,
        targetIds: IntArray,
        targets: DoubleArray,
        monotonicMillis: () -> Long,
        evidenceSource: GuidanceImageEvidenceSource,
    ): NativeGuidancePipeline {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Guidance start requires UI thread" }
        check(!released && activityResumed && frameSource === source) { "Camera surface is unavailable" }
        stopGuidance()
        val savedIds = targetIds.copyOf()
        val pipeline = NativeGuidancePipeline.open(
            sceneId, geometry, savedIds, targets, monotonicMillis,
            NativeGuidanceSink { snapshot ->
                publishGuidance(source, anchor, snapshot, monotonicMillis)
            },
        )
        val binding = try {
            RecognitionCaptureBinding(source, anchor, pipeline, monotonicMillis, savedIds, evidenceSource)
        } catch (failure: RuntimeException) {
            pipeline.close()
            throw failure
        }
        recognitionSession = binding
        cameraRenderer.recognitionBinding = binding
        return pipeline
    }

    internal fun stopGuidance() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Guidance stop requires UI thread" }
        val owned = recognitionSession
        recognitionSession = null
        cameraRenderer.recognitionBinding = null
        cameraRenderer.guideBinding = null
        owned?.close()
    }

    /**
     * The producer supplies its own core observation clock; camera timestamps
     * are not assumed to share its origin. No native work is done on the UI thread.
     */
    internal fun publishGuidance(
        source: ArCameraFrameSource,
        anchor: SceneAnchorSnapshot,
        snapshot: NativeWorldFrameSnapshot,
        monotonicMillis: () -> Long,
    ): Boolean {
        if (released || !activityResumed || frameSource !== source) return false
        cameraRenderer.guideBinding = GuideOverlayBinding(source, anchor, snapshot, monotonicMillis)
        return true
    }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(cameraRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onActivityResume() {
        if (released || activityResumed) return
        activityResumed = true
        onResume()
    }

    override fun onActivityPause() {
        baselineCapture.cancel()
        stopGuidance()
        if (!activityResumed) return
        activityResumed = false
        onPause()
    }

    override fun releaseSurface() {
        if (released) return
        released = true
        stopGuidance()
        queueEvent(cameraRenderer::release)
        onActivityPause()
    }
}

private class RecognitionCaptureBinding(
    val source: ArCameraFrameSource,
    val anchor: SceneAnchorSnapshot,
    val pipeline: NativeGuidancePipeline,
    val monotonicMillis: () -> Long,
    savedIds: IntArray,
    evidenceSource: GuidanceImageEvidenceSource,
) : AutoCloseable {
    private val bridge = ImageGuidanceBridge(pipeline, savedIds, evidenceSource)
    private val detector = MlKitImageDetector.create(object : CpuImageDetectionSink {
        override fun accept(capture: NativeImageCapture, image: CpuCameraImage, result: ImageDetectionResult) {
            val delivery = try {
                if (evidenceSource is CpuImageEvidenceSource) {
                    evidenceSource.withImage(image) { bridge.process(capture, result) }
                } else {
                    bridge.process(capture, result)
                }
            } catch (_: RuntimeException) {
                this@RecognitionCaptureBinding.close()
                return
            }
            val processing = delivery.processing
            if (processing is NativeFrameProcessing.Failed ||
                (processing is NativeFrameProcessing.Dropped && processing.reason == NativeFrameDropReason.CLOSED)
            ) this@RecognitionCaptureBinding.close()
        }

        override fun close() {
            if (evidenceSource is CpuImageEvidenceSource) evidenceSource.close()
        }
    })

    // Accessed only on the GL thread.
    var lastImageTimestamp = 0L

    fun submit(capture: NativeImageCapture, image: CpuCameraImage) {
        if (capture.epoch === pipeline.epoch) detector.submit(capture, image)
    }

    override fun close() {
        try {
            pipeline.close()
        } finally {
            detector.close()
        }
    }
}

private data class GuideOverlayBinding(
    val source: ArCameraFrameSource,
    val anchor: SceneAnchorSnapshot,
    val snapshot: NativeWorldFrameSnapshot,
    val monotonicMillis: () -> Long,
)

private class CameraSurfaceRenderer(
    private val baselineCapture: BaselineCaptureMailbox,
    private val displayRotation: () -> Int,
    private val onFailure: (CameraBackgroundSurfaceFailure) -> Unit,
    private val onTrackingDiagnostics: (ArTrackingDiagnostics?) -> Unit,
    private val onHorizontalPlaneState: (HorizontalPlaneState?) -> Unit,
    private val onSceneAnchorState: (SceneAnchorState?) -> Unit,
    private val onRecognitionFailure: (RecognitionCaptureBinding) -> Unit,
) : GLSurfaceView.Renderer {
    private val backgroundRenderer = CameraBackgroundRenderer()
    private val guideRenderer = GuideOverlayRenderer()

    @Volatile
    var guideBinding: GuideOverlayBinding? = null

    @Volatile
    var recognitionBinding: RecognitionCaptureBinding? = null

    @Volatile
    var frameSource: ArCameraFrameSource? = null
        set(value) {
            if (field !== value) guideBinding = null
            field = value
            boundSource = null
        }

    private var boundSource: ArCameraFrameSource? = null
    private var widthPx = 0
    private var heightPx = 0
    private var lastFailure: CameraBackgroundSurfaceFailure? = null
    private val diagnosticsDeduplicator = TrackingDiagnosticsDeduplicator()
    private val planeStateDeduplicator = HorizontalPlaneStateDeduplicator()
    private val anchorStateDeduplicator = SceneAnchorStateDeduplicator()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        when (val result = backgroundRenderer.create()) {
            is CameraBackgroundRenderResult.Created -> Unit
            is CameraBackgroundRenderResult.Rejected -> fail(
                CameraBackgroundSurfaceFailure.Renderer(result.reason),
            )
            else -> fail(
                CameraBackgroundSurfaceFailure.Renderer(
                    CameraBackgroundFailureReason.GlOperationFailed,
                ),
            )
        }
        guideBinding = null
        if (!guideRenderer.create()) {
            fail(CameraBackgroundSurfaceFailure.Renderer(CameraBackgroundFailureReason.GlOperationFailed))
        }
        boundSource = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        widthPx = width
        heightPx = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val source = frameSource ?: return
        if (boundSource !== source) {
            val createResult = backgroundRenderer.create()
            val textureId = (createResult as? CameraBackgroundRenderResult.Created)?.textureId
                ?: return failRenderer(createResult)
            when (val bindResult = source.bindCameraTexture(textureId)) {
                ArCameraFrameResult.TextureBound -> boundSource = source
                is ArCameraFrameResult.Rejected -> return fail(
                    CameraBackgroundSurfaceFailure.Frame(bindResult.reason),
                )
                else -> return fail(
                    CameraBackgroundSurfaceFailure.Renderer(
                        CameraBackgroundFailureReason.GlOperationFailed,
                    ),
                )
            }
        }

        when (val frameResult = source.updateCameraFrame(
            displayRotation = displayRotation(),
            widthPx = widthPx,
            heightPx = heightPx,
        )) {
            is ArCameraFrameResult.Updated -> {
                if (diagnosticsDeduplicator.shouldEmit(frameResult.frame.trackingDiagnostics)) {
                    onTrackingDiagnostics(frameResult.frame.trackingDiagnostics)
                }
                if (planeStateDeduplicator.shouldEmit(frameResult.frame.horizontalPlaneState)) {
                    onHorizontalPlaneState(frameResult.frame.horizontalPlaneState)
                }
                if (anchorStateDeduplicator.shouldEmit(frameResult.frame.sceneAnchorState)) {
                    onSceneAnchorState(frameResult.frame.sceneAnchorState)
                }
                frameResult.frame.transformedTextureCoordinates?.let { coordinates ->
                    val coordinateResult = backgroundRenderer.updateTextureCoordinates(coordinates)
                    if (coordinateResult is CameraBackgroundRenderResult.Rejected) {
                        return fail(CameraBackgroundSurfaceFailure.Renderer(coordinateResult.reason))
                    }
                }
                when (val drawResult = backgroundRenderer.draw(frameResult.frame.timestampNanos)) {
                    CameraBackgroundRenderResult.Drawn -> {
                        if (drawGuidance(source, frameResult.frame)) {
                            lastFailure = null
                            baselineCapture.offer(frameResult.frame)
                            dispatchRecognition(source, frameResult.frame)
                        }
                    }
                    CameraBackgroundRenderResult.Skipped -> lastFailure = null
                    is CameraBackgroundRenderResult.Rejected -> fail(
                        CameraBackgroundSurfaceFailure.Renderer(drawResult.reason),
                    )
                    else -> Unit
                }
            }
            is ArCameraFrameResult.Rejected -> {
                guideBinding = null
                fail(CameraBackgroundSurfaceFailure.Frame(frameResult.reason))
            }
            else -> Unit
        }
    }

    private fun dispatchRecognition(source: ArCameraFrameSource, frame: ArCameraFrame) {
        val binding = recognitionBinding ?: return
        val anchor = (frame.sceneAnchorState as? SceneAnchorState.Tracking)?.anchor
        if (binding.source !== source || anchor == null ||
            anchor.id != binding.anchor.id || anchor.pose != binding.anchor.pose
        ) return
        val image = (frame.cpuImageResult as? CpuImageAcquisitionResult.Acquired)?.image ?: return
        if (image.timestampNanos <= binding.lastImageTimestamp) return
        try {
            val capture = NativeImageCaptureFactory.capture(
                frame, binding.pipeline.epoch, binding.monotonicMillis(),
            ) ?: return
            binding.lastImageTimestamp = image.timestampNanos
            binding.submit(capture, image)
        } catch (_: RuntimeException) {
            recognitionBinding = null
            guideBinding = null
            // Closing may serialize with publication; keep that wait off the GL thread.
            onRecognitionFailure(binding)
            fail(CameraBackgroundSurfaceFailure.Renderer(CameraBackgroundFailureReason.GlOperationFailed))
        }
    }

    private fun drawGuidance(source: ArCameraFrameSource, frame: ArCameraFrame): Boolean {
        val binding = guideBinding ?: return true
        val anchor = (frame.sceneAnchorState as? SceneAnchorState.Tracking)?.anchor
        if (frame.trackingDiagnostics.phase != ArTrackingPhase.Tracking ||
            binding.source !== source || anchor == null ||
            anchor.id != binding.anchor.id || anchor.pose != binding.anchor.pose
        ) {
            guideBinding = null
            return true
        }
        val view = frame.viewMatrix ?: return true
        val projection = frame.projectionMatrix ?: return true
        val success = try {
            val presentation = binding.snapshot.presentationAt(binding.monotonicMillis())
            guideRenderer.draw(NativeGuideGeometry.build(presentation), view, projection)
        } catch (_: RuntimeException) {
            false
        }
        if (!success) {
            guideBinding = null
            fail(CameraBackgroundSurfaceFailure.Renderer(CameraBackgroundFailureReason.GlOperationFailed))
        }
        return success
    }

    fun release() {
        recognitionBinding = null
        guideBinding = null
        guideRenderer.release()
        boundSource = null
        frameSource = null
        backgroundRenderer.release()
        diagnosticsDeduplicator.reset()
        planeStateDeduplicator.reset()
        anchorStateDeduplicator.reset()
        onTrackingDiagnostics(null)
        onHorizontalPlaneState(null)
        onSceneAnchorState(null)
    }

    private fun failRenderer(result: CameraBackgroundRenderResult) {
        val reason = (result as? CameraBackgroundRenderResult.Rejected)?.reason
            ?: CameraBackgroundFailureReason.GlOperationFailed
        fail(CameraBackgroundSurfaceFailure.Renderer(reason))
    }

    private fun fail(failure: CameraBackgroundSurfaceFailure) {
        if (failure == lastFailure) return
        lastFailure = failure
        onFailure(failure)
    }
}
