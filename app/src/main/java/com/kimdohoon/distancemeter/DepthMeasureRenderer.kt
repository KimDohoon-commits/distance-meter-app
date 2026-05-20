package com.kimdohoon.distancemeter

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.kimdohoon.distancemeter.rendering.BackgroundRenderer
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DepthMeasureRenderer(
    private val onReady: () -> Unit,
    private val onDistanceMeasured: (distanceM: Float, debugInfo: String) -> Unit,
    private val onError: (msg: String) -> Unit
) : GLSurfaceView.Renderer {

    var session: Session? = null

    private val backgroundRenderer = BackgroundRenderer()
    private var viewWidth = 1
    private var viewHeight = 1
    private var initialized = false

    // 시간 평균용 히스토리 (최근 5프레임)
    private val frameHistory = ArrayDeque<Float>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val sess = session ?: return

        try {
            sess.setCameraTextureNames(intArrayOf(backgroundRenderer.getCameraTextureId()))

            val frame = sess.update()

            // 카메라 배경 그리기
            backgroundRenderer.draw(frame)

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return

            // 처음 트래킹 성공 시 준비 완료 알림
            if (!initialized) {
                initialized = true
                onReady()
            }

            // 매 프레임마다 십자선 중앙 거리 측정
            measureDepthAtCenter(frame)

        } catch (e: Exception) {
            // 무시
        }
    }

    private fun measureDepthAtCenter(frame: Frame) {
        try {
            // acquireDepthImage16Bits = ARCore가 이미 내부적으로 스무딩한 depth
            val depthImage = frame.acquireDepthImage16Bits()

            val cx = depthImage.width  / 2
            val cy = depthImage.height / 2
            val radius = 3  // 7x7 = 49픽셀 샘플링

            val plane  = depthImage.planes[0]
            val buf    = plane.buffer
            val samples = mutableListOf<Int>()

            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val x = (cx + dx).coerceIn(0, depthImage.width  - 1)
                    val y = (cy + dy).coerceIn(0, depthImage.height - 1)

                    val idx = y * plane.rowStride + x * plane.pixelStride
                    val lo  = buf.get(idx).toInt()     and 0xFF
                    val hi  = buf.get(idx + 1).toInt() and 0xFF
                    val mm  = lo or (hi shl 8)

                    if (mm > 0) samples.add(mm)
                }
            }

            depthImage.close()

            if (samples.isEmpty()) {
                onDistanceMeasured(-1f, "측정 불가 (0값만 수신)")
                return
            }

            // 공간 중앙값 (outlier에 강함)
            val medianMm = samples.sorted()[samples.size / 2]

            // 시간 평균 (프레임 떨림 제거, 최근 5프레임)
            if (frameHistory.size >= 5) frameHistory.removeFirst()
            frameHistory.addLast(medianMm.toFloat())
            val smoothedMm = frameHistory.average().toFloat()

            val debugInfo = "샘플 ${samples.size}/${radius*2+1}x${radius*2+1}  |  ${smoothedMm.toInt()}mm"
            onDistanceMeasured(smoothedMm / 1000f, debugInfo)

        } catch (e: NotYetAvailableException) {
            // 준비 중, 무시
        } catch (e: Exception) {
            onError("측정 오류: ${e.message}")
        }
    }
}
