package com.kimdohoon.distancemeter.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ARCore 카메라 영상을 GL 화면에 그려주는 렌더러
 */
class BackgroundRenderer {

    private var programId = -1
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var textureHandle = -1
    private var cameraTextureId = -1

    // 화면 꽉 채우는 사각형 NDC 좌표
    private val ndcQuadCoords: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_NDC.size * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .also { it.put(QUAD_NDC).position(0) }

    // ARCore가 채워줄 텍스처 UV 좌표 (회전 보정 포함)
    private val transformedUVs = FloatArray(QUAD_UV_SRC.size)
    private val transformedUVsBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_UV_SRC.size * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(programId, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "a_TexCoord")
        textureHandle = GLES20.glGetUniformLocation(programId, "u_Texture")
    }

    fun getCameraTextureId() = cameraTextureId

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            // ARCore가 기기 회전에 맞게 UV 좌표를 변환해 줌
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                FloatBuffer.wrap(QUAD_NDC),
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedUVsBuffer.also { buf ->
                    buf.position(0)
                    // output 버퍼로 사용
                }
            )
            transformedUVsBuffer.position(0)
            transformedUVsBuffer.get(transformedUVs)
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(textureHandle, 0)

        ndcQuadCoords.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, ndcQuadCoords)
        GLES20.glEnableVertexAttribArray(positionHandle)

        transformedUVsBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, transformedUVsBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glUseProgram(0)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ── 헬퍼 ─────────────────────────────────────────

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vert)
            GLES20.glAttachShader(it, frag)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    companion object {
        private const val FLOAT_SIZE = 4

        // 화면 꽉 채우는 NDC 좌표 (Triangle Strip)
        private val QUAD_NDC = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )

        // 초기 UV (ARCore가 덮어씀)
        private val QUAD_UV_SRC = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        private val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """.trimIndent()
    }
}
