package com.modose.app.ar.render

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import com.modose.app.ar.session.ArCameraFrameFailureReason
import com.modose.app.ar.session.ArCameraFrameResult
import com.modose.app.ar.session.ArCameraFrameSource
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
) : GLSurfaceView(context), CameraBackgroundSurfaceController {
    private val cameraRenderer = CameraSurfaceRenderer(
        displayRotation = { display?.rotation ?: Surface.ROTATION_0 },
        onFailure = { failure -> post { onFailure(failure) } },
    )
    private var activityResumed = false

    var frameSource: ArCameraFrameSource?
        get() = cameraRenderer.frameSource
        set(value) {
            cameraRenderer.frameSource = value
        }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(cameraRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onActivityResume() {
        if (activityResumed) return
        activityResumed = true
        onResume()
    }

    override fun onActivityPause() {
        if (!activityResumed) return
        activityResumed = false
        onPause()
    }

    override fun releaseSurface() {
        queueEvent(cameraRenderer::release)
        onActivityPause()
    }
}

private class CameraSurfaceRenderer(
    private val displayRotation: () -> Int,
    private val onFailure: (CameraBackgroundSurfaceFailure) -> Unit,
) : GLSurfaceView.Renderer {
    private val backgroundRenderer = CameraBackgroundRenderer()

    @Volatile
    var frameSource: ArCameraFrameSource? = null
        set(value) {
            field = value
            boundSource = null
        }

    private var boundSource: ArCameraFrameSource? = null
    private var widthPx = 0
    private var heightPx = 0
    private var lastFailure: CameraBackgroundSurfaceFailure? = null

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
                frameResult.frame.transformedTextureCoordinates?.let { coordinates ->
                    val coordinateResult = backgroundRenderer.updateTextureCoordinates(coordinates)
                    if (coordinateResult is CameraBackgroundRenderResult.Rejected) {
                        return fail(CameraBackgroundSurfaceFailure.Renderer(coordinateResult.reason))
                    }
                }
                when (val drawResult = backgroundRenderer.draw(frameResult.frame.timestampNanos)) {
                    CameraBackgroundRenderResult.Drawn,
                    CameraBackgroundRenderResult.Skipped,
                    -> lastFailure = null
                    is CameraBackgroundRenderResult.Rejected -> fail(
                        CameraBackgroundSurfaceFailure.Renderer(drawResult.reason),
                    )
                    else -> Unit
                }
            }
            is ArCameraFrameResult.Rejected -> fail(
                CameraBackgroundSurfaceFailure.Frame(frameResult.reason),
            )
            else -> Unit
        }
    }

    fun release() {
        boundSource = null
        frameSource = null
        backgroundRenderer.release()
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
