package com.kimdohoon.distancemeter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.*

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvDistance: TextView
    private lateinit var tvTapInfo: TextView
    private lateinit var tvStatus: TextView

    private var session: Session? = null
    private lateinit var renderer: DepthMeasureRenderer

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        tvDistance = findViewById(R.id.tvDistance)
        tvTapInfo = findViewById(R.id.tvTapInfo)
        tvStatus = findViewById(R.id.tvStatus)

        renderer = DepthMeasureRenderer(
            onReady = {
                runOnUiThread {
                    tvStatus.visibility = View.GONE
                }
            },
            onDistanceMeasured = { distanceM, debugInfo ->
                runOnUiThread {
                    if (distanceM < 0) {
                        tvDistance.text = "측정 불가"
                    } else {
                        tvDistance.text = "%.2f m".format(distanceM)
                    }
                    tvTapInfo.text = debugInfo
                }
            },
            onError = { msg ->
                runOnUiThread {
                    tvStatus.text = msg
                    tvStatus.visibility = View.VISIBLE
                }
            }
        )

        surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        val btnGpsMode = findViewById<Button>(R.id.btnGpsMode)
        btnGpsMode.setOnClickListener {
            startActivity(Intent(this, GpsTriangulationActivity::class.java))
        }

        // GPS 버튼 상태바 아래로
        ViewCompat.setOnApplyWindowInsetsListener(btnGpsMode) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin  = bars.top + 12
                rightMargin = bars.right + 12
            }
            v.requestLayout()
            insets
        }
    }

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }

        try {
            // ARCore 설치 확인
            when (ArCoreApk.getInstance().requestInstall(this, true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return
                ArCoreApk.InstallStatus.INSTALLED -> {}
            }

            if (session == null) {
                session = Session(this).also { sess ->
                    val config = Config(sess).apply {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

                        if (sess.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                        } else {
                            runOnUiThread {
                                tvStatus.text = "⚠️ 이 기기는 Depth를 지원하지 않습니다"
                                tvStatus.visibility = View.VISIBLE
                            }
                        }
                    }
                    sess.configure(config)
                }
                renderer.session = session
            }

            session?.resume()
            surfaceView.onResume()

        } catch (e: UnavailableUserDeclinedInstallationException) {
            showToast("ARCore 설치가 필요합니다")
        } catch (e: UnavailableArcoreNotInstalledException) {
            showToast("ARCore를 설치해주세요")
        } catch (e: UnavailableApkTooOldException) {
            showToast("ARCore를 업데이트해주세요")
        } catch (e: UnavailableSdkTooOldException) {
            showToast("앱을 업데이트해주세요")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showToast("이 기기는 ARCore를 지원하지 않습니다")
        } catch (e: Exception) {
            showToast("ARCore 초기화 오류: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    // ── 권한 ──────────────────────────────────────────────

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() =
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                onResume()
            } else {
                showToast("카메라 권한이 필요합니다")
                finish()
            }
        }
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
