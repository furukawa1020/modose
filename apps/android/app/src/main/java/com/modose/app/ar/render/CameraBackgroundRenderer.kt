package com.modose.app.ar.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class CameraBackgroundFailureReason {
    NotInitialized,
    WrongGlThread,
    InvalidTextureCoordinates,
    ShaderCompilationFailed,
    ProgramLinkFailed,
    GlOperationFailed,
}

sealed interface CameraBackgroundRenderResult {
    data class Created(val textureId: Int) : CameraBackgroundRenderResult
    data object CoordinatesUpdated : CameraBackgroundRenderResult
    data object Drawn : CameraBackgroundRenderResult
    data object Skipped : CameraBackgroundRenderResult
    data object Released : CameraBackgroundRenderResult
    data class Rejected(val reason: CameraBackgroundFailureReason) : CameraBackgroundRenderResult
}

object CameraBackgroundDrawPolicy {
    fun canDraw(
        initialized: Boolean,
        hasTextureCoordinates: Boolean,
        timestampNanos: Long,
    ): Boolean = initialized && hasTextureCoordinates && timestampNanos > 0L
}

class CameraBackgroundRenderer {
    private val vertexBuffer = floatBuffer(OPEN_GL_QUAD)
    private val textureBuffer = floatBuffer(FloatArray(COORDINATE_COUNT))

    private var glThreadId: Long? = null
    private var textureId = 0
    private var programId = 0
    private var positionLocation = -1
    private var textureCoordinateLocation = -1
    private var textureUniformLocation = -1
    private var hasTextureCoordinates = false

    fun create(): CameraBackgroundRenderResult {
        claimGlThread()
        releaseResources()

        textureId = createExternalTexture()
        if (textureId == 0) return rejectAndRelease(CameraBackgroundFailureReason.GlOperationFailed)

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertexShader == 0 || fragmentShader == 0) {
            if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
            return rejectAndRelease(CameraBackgroundFailureReason.ShaderCompilationFailed)
        }

        programId = linkProgram(vertexShader, fragmentShader)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (programId == 0) return rejectAndRelease(CameraBackgroundFailureReason.ProgramLinkFailed)

        positionLocation = GLES20.glGetAttribLocation(programId, "a_Position")
        textureCoordinateLocation = GLES20.glGetAttribLocation(programId, "a_TexCoord")
        textureUniformLocation = GLES20.glGetUniformLocation(programId, "u_CameraTexture")
        if (positionLocation < 0 || textureCoordinateLocation < 0 || textureUniformLocation < 0) {
            return rejectAndRelease(CameraBackgroundFailureReason.ProgramLinkFailed)
        }
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            return rejectAndRelease(CameraBackgroundFailureReason.GlOperationFailed)
        }
        return CameraBackgroundRenderResult.Created(textureId)
    }

    fun updateTextureCoordinates(coordinates: FloatArray): CameraBackgroundRenderResult {
        if (!isGlThread()) return rejected(CameraBackgroundFailureReason.WrongGlThread)
        if (coordinates.size != COORDINATE_COUNT) {
            return rejected(CameraBackgroundFailureReason.InvalidTextureCoordinates)
        }
        textureBuffer.position(0)
        textureBuffer.put(coordinates)
        textureBuffer.position(0)
        hasTextureCoordinates = true
        return CameraBackgroundRenderResult.CoordinatesUpdated
    }

    fun draw(timestampNanos: Long): CameraBackgroundRenderResult {
        if (!isGlThread()) return rejected(CameraBackgroundFailureReason.WrongGlThread)
        if (programId == 0 || textureId == 0) {
            return rejected(CameraBackgroundFailureReason.NotInitialized)
        }
        if (!CameraBackgroundDrawPolicy.canDraw(true, hasTextureCoordinates, timestampNanos)) {
            return CameraBackgroundRenderResult.Skipped
        }

        GLES20.glDepthMask(false)
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniformLocation, 0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
        GLES20.glVertexAttribPointer(
            textureCoordinateLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(textureCoordinateLocation)
        GLES20.glDepthMask(true)
        return if (GLES20.glGetError() == GLES20.GL_NO_ERROR) {
            CameraBackgroundRenderResult.Drawn
        } else {
            rejected(CameraBackgroundFailureReason.GlOperationFailed)
        }
    }

    fun release(): CameraBackgroundRenderResult {
        if (glThreadId != null && !isGlThread()) {
            return rejected(CameraBackgroundFailureReason.WrongGlThread)
        }
        releaseResources()
        glThreadId = null
        return CameraBackgroundRenderResult.Released
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return textures[0]
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun linkProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES20.glCreateProgram()
        if (program == 0) return 0
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun releaseResources() {
        if (programId != 0) GLES20.glDeleteProgram(programId)
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        programId = 0
        textureId = 0
        hasTextureCoordinates = false
    }

    private fun rejectAndRelease(
        reason: CameraBackgroundFailureReason,
    ): CameraBackgroundRenderResult.Rejected {
        releaseResources()
        return rejected(reason)
    }

    private fun claimGlThread() {
        glThreadId = Thread.currentThread().id
    }

    private fun isGlThread(): Boolean = glThreadId == Thread.currentThread().id

    private fun rejected(
        reason: CameraBackgroundFailureReason,
    ) = CameraBackgroundRenderResult.Rejected(reason)

    private companion object {
        const val COORDINATE_COUNT = 8
        val OPEN_GL_QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        const val VERTEX_SHADER = """
            attribute vec2 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                v_TexCoord = a_TexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_CameraTexture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_CameraTexture, v_TexCoord);
            }
        """

        fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
            .allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }
}
