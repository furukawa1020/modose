package com.modose.app.ar.render

import android.opengl.GLES20
import android.opengl.Matrix
import com.modose.app.core.NativeGuideDrawData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owned exclusively by CameraSurfaceRenderer's GL thread. Opaque annotation pass. */
internal class GuideOverlayRenderer {
    private var owner: Thread? = null
    private var program = 0
    private var position = -1
    private var matrixUniform = -1
    private var colorUniform = -1
    private val matrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(64 * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Called for a new EGL context; old context handles must not be deleted here. */
    fun create(): Boolean {
        owner = Thread.currentThread()
        program = 0
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec3 a_Position; uniform mat4 u_Matrix; void main() { gl_Position = u_Matrix * vec4(a_Position, 1.0); }")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; uniform vec4 u_Color; void main() { gl_FragColor = u_Color; }")
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) GLES20.glDeleteShader(vertex)
            if (fragment != 0) GLES20.glDeleteShader(fragment)
            return false
        }
        program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (program == 0) return false
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        position = GLES20.glGetAttribLocation(program, "a_Position")
        matrixUniform = GLES20.glGetUniformLocation(program, "u_Matrix")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        if (linked[0] == 0 || position < 0 || matrixUniform < 0 || colorUniform < 0 ||
            GLES20.glGetError() != GLES20.GL_NO_ERROR
        ) {
            release()
            return false
        }
        return true
    }

    fun draw(data: NativeGuideDrawData, view: FloatArray, projection: FloatArray): Boolean {
        if (owner !== Thread.currentThread()) return false
        if (data is NativeGuideDrawData.Empty) return true
        if (data !is NativeGuideDrawData.Lines || program == 0) return false
        if (view.size != 16 || projection.size != 16 ||
            view.any { !it.isFinite() } || projection.any { !it.isFinite() } ||
            data.vertices.size !in 6..192 || data.vertices.size % 6 != 0 ||
            data.vertices.any { !it.isFinite() }
        ) return false
        Matrix.multiplyMM(matrix, 0, projection, 0, view, 0)
        if (matrix.any { !it.isFinite() }) return false
        vertices.clear()
        vertices.put(data.vertices)
        vertices.flip()

        val depthEnabled = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
        val depthMask = BooleanArray(1)
        val previousProgram = IntArray(1)
        GLES20.glGetBooleanv(GLES20.GL_DEPTH_WRITEMASK, depthMask, 0)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, previousProgram, 0)
        try {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(false)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(matrixUniform, 1, false, matrix, 0)
            if (data.orientationCheck) GLES20.glUniform4f(colorUniform, 1f, 0.7f, 0.1f, 1f)
            else GLES20.glUniform4f(colorUniform, 0.1f, 1f, 0.7f, 1f)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, data.vertices.size / 3)
        } finally {
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glUseProgram(previousProgram[0])
            GLES20.glDepthMask(depthMask[0])
            if (depthEnabled) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            else GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        }
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    fun release() {
        if (owner !== Thread.currentThread()) return
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
        owner = null
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        if (id == 0) return 0
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(id)
            return 0
        }
        return id
    }
}
