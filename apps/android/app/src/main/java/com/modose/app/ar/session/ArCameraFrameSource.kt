package com.modose.app.ar.session

data class ArCameraFrame(
    val timestampNanos: Long,
    val transformedTextureCoordinates: FloatArray?,
)

enum class ArCameraFrameFailureReason {
    SessionNotResumed,
    TextureNotBound,
    InvalidSurfaceSize,
    WrongGlThread,
    CameraUnavailable,
    Unknown,
}

sealed interface ArCameraFrameResult {
    data object TextureBound : ArCameraFrameResult
    data class Updated(val frame: ArCameraFrame) : ArCameraFrameResult
    data class Rejected(val reason: ArCameraFrameFailureReason) : ArCameraFrameResult
}

interface ArCameraFrameSource {
    fun bindCameraTexture(textureId: Int): ArCameraFrameResult

    fun updateCameraFrame(
        displayRotation: Int,
        widthPx: Int,
        heightPx: Int,
    ): ArCameraFrameResult
}
