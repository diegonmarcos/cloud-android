package com.diegonmarcos.superapp

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView running the matrix-rain background shader adapted from
 * front/e-Root/src/shaders/effects/mode-36-matrix-rain.glsl (combined
 * with the hash helper from fragment-base.glsl). Used as the back
 * layer of [Home3DFragment]; the wireframe cube floats on top.
 *
 * Single-shader, no mode dispatcher — the cube view itself owns the
 * rotation interaction (or rather: doesn't, since user disabled it).
 */
class ShaderBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    init {
        setEGLContextClientVersion(2)
        setRenderer(MatrixRainRenderer(context))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private class MatrixRainRenderer(private val ctx: Context) : Renderer {
        private var program = 0
        private var aPositionLoc  = -1
        private var uTimeLoc      = -1
        private var uResolutionLoc = -1
        private var startNs = 0L
        private var viewW = 1f
        private var viewH = 1f

        // Two triangles covering the clip-space quad [-1, 1]².
        private val quad: FloatBuffer = ByteBuffer
            .allocateDirect(8 * 4 * 2)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(
                    -1f, -1f,
                     1f, -1f,
                    -1f,  1f,
                     1f,  1f,
                ))
                position(0)
            }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            startNs = System.nanoTime()
            val vs = loadAsset("shaders/cube_bg_vertex.glsl")
            val fs = loadAsset("shaders/cube_bg_fragment.glsl")
            val vsId = compile(GLES20.GL_VERTEX_SHADER, vs)
            val fsId = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            program = GLES20.glCreateProgram().also { id ->
                GLES20.glAttachShader(id, vsId)
                GLES20.glAttachShader(id, fsId)
                GLES20.glLinkProgram(id)
            }
            aPositionLoc   = GLES20.glGetAttribLocation(program, "aPosition")
            uTimeLoc       = GLES20.glGetUniformLocation(program, "uTime")
            uResolutionLoc = GLES20.glGetUniformLocation(program, "uResolution")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.toFloat(); viewH = height.toFloat()
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val t = (System.nanoTime() - startNs) / 1_000_000_000f
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform1f(uTimeLoc, t)
            GLES20.glUniform2f(uResolutionLoc, viewW, viewH)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(
                aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quad,
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionLoc)
        }

        private fun loadAsset(path: String): String =
            ctx.assets.open(path).bufferedReader().use { it.readText() }

        private fun compile(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            // GLES20.glGetShaderiv has TWO overloads — (Int, Int, IntBuffer)
            // and (Int, Int, IntArray, Int). The 3-arg call only resolves
            // for IntBuffer; the IntArray variant needs the offset.
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(id)
                GLES20.glDeleteShader(id)
                throw RuntimeException("shader compile failed: $log")
            }
            return id
        }
    }
}
