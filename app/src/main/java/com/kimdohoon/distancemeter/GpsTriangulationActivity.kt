package com.kimdohoon.distancemeter

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.*

class GpsTriangulationActivity : AppCompatActivity(), SensorEventListener {

    // ── UI ──────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var tvAzimuth: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvPointA: TextView
    private lateinit var tvPointB: TextView
    private lateinit var tvGuide: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvDistanceLabel: TextView
    private lateinit var btnRecordA: Button
    private lateinit var btnRecordB: Button
    private lateinit var btnReset: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var tvZoomLevel: TextView

    // ── 카메라/줌 ─────────────────────────────────────────────
    private var camera: Camera? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // ── 센서 ────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var smoothedAzimuth = 0f
    private var currentAzimuth = 0f

    // ── GPS ─────────────────────────────────────────────────
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null

    // ── 측정 데이터 ─────────────────────────────────────────
    data class AimPoint(val lat: Double, val lon: Double, val azimuth: Float)

    private var pointA: AimPoint? = null
    private var pointB: AimPoint? = null

    companion object {
        private const val PERM_CODE = 200
    }

    // ── 생명주기 ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gps_triangulation)

        previewView      = findViewById(R.id.previewView)
        tvAzimuth        = findViewById(R.id.tvAzimuth)
        tvGpsStatus      = findViewById(R.id.tvGpsStatus)
        tvPointA         = findViewById(R.id.tvPointA)
        tvPointB         = findViewById(R.id.tvPointB)
        tvGuide          = findViewById(R.id.tvGuide)
        tvDistance       = findViewById(R.id.tvDistance)
        tvDistanceLabel  = findViewById(R.id.tvDistanceLabel)
        btnRecordA       = findViewById(R.id.btnRecordA)
        btnRecordB       = findViewById(R.id.btnRecordB)
        btnReset         = findViewById(R.id.btnReset)
        btnZoomIn        = findViewById(R.id.btnZoomIn)
        btnZoomOut       = findViewById(R.id.btnZoomOut)
        tvZoomLevel      = findViewById(R.id.tvZoomLevel)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // 상태바 / 네비게이션바 영역 피하기
        val topBar     = findViewById<LinearLayout>(R.id.topBar)
        val bottomPanel = findViewById<LinearLayout>(R.id.bottomPanel)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(14.dp, bars.top + 14.dp, 14.dp, 14.dp)
            bottomPanel.setPadding(16.dp, 16.dp, 16.dp, bars.bottom + 16.dp)
            insets
        }

        btnRecordA.setOnClickListener { recordPointA() }
        btnRecordB.setOnClickListener { recordPointB() }
        btnReset.setOnClickListener   { reset() }

        // +/- 버튼 줌
        btnZoomIn.setOnClickListener  { adjustZoom(1.3f) }
        btnZoomOut.setOnClickListener { adjustZoom(1f / 1.3f) }

        // 핀치 줌
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    adjustZoom(detector.scaleFactor)
                    return true
                }
            })
        previewView.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            v.performClick()
            true
        }

        checkPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    // ── 권한 ─────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasPerm(Manifest.permission.CAMERA))
            needed += Manifest.permission.CAMERA
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION))
            needed += Manifest.permission.ACCESS_FINE_LOCATION

        if (needed.isEmpty()) {
            startCamera()
            startGps()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_CODE)
        }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startCamera()
                startGps()
            } else {
                Toast.makeText(this, "카메라 및 위치 권한이 필요합니다", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ── 카메라 ────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview
                )
                // 줌 상태 실시간 반영
                camera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
                    tvZoomLevel.text = "%.1fx".format(zoomState.zoomRatio)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun adjustZoom(factor: Float) {
        val cam = camera ?: return
        val currentRatio = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val minRatio     = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
        val maxRatio     = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 10f
        val newRatio     = (currentRatio * factor).coerceIn(minRatio, maxRatio)
        cam.cameraControl.setZoomRatio(newRatio)
    }

    // ── GPS ──────────────────────────────────────────────────

    private val locationListener = LocationListener { location ->
        currentLocation = location
        val acc = "±%.0fm".format(location.accuracy)
        tvGpsStatus.text = "GPS 정확도: $acc"
    }

    private fun startGps() {
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            // GPS + Network 둘 다 시도 (더 빨리 잡힘)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0.5f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?.let { currentLocation = it }
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000L, 0.5f, locationListener
                )
                if (currentLocation == null) {
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?.let { currentLocation = it }
                }
            }
        } catch (e: Exception) {
            tvGpsStatus.text = "GPS 오류: ${e.message}"
        }
    }

    // ── 나침반 (Rotation Vector) ──────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        SensorManager.getOrientation(rotMatrix, orientation)

        var az = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (az < 0) az += 360f

        // 로우패스 필터 (0/360 경계 처리 포함)
        smoothedAzimuth = lowPassAngle(az, smoothedAzimuth)
        currentAzimuth = smoothedAzimuth

        tvAzimuth.text = "방위각: %.1f°  %s".format(currentAzimuth, directionLabel(currentAzimuth))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── 측정 버튼 ─────────────────────────────────────────────

    private fun recordPointA() {
        val loc = currentLocation
        if (loc == null) {
            toast("GPS 신호를 기다리는 중입니다"); return
        }
        pointA = AimPoint(loc.latitude, loc.longitude, currentAzimuth)
        pointB = null
        btnRecordB.isEnabled = true
        tvPointA.text = "A ✓  %.1f°  %s".format(currentAzimuth, directionLabel(currentAzimuth))
        tvPointA.setTextColor(0xFF4FC3F7.toInt())
        tvPointB.text = "B: 대기 중"
        tvPointB.setTextColor(0xAAFFFFFF.toInt())
        tvDistance.text = ""
        tvDistanceLabel.text = ""
        tvGuide.text = "옆으로 10~20m 이동 후 같은 목표물에 조준하고 B 찍기를 누르세요"
    }

    private fun recordPointB() {
        val loc = currentLocation
        if (loc == null) {
            toast("GPS 신호를 기다리는 중입니다"); return
        }
        if (pointA == null) {
            toast("먼저 A를 찍으세요"); return
        }
        pointB = AimPoint(loc.latitude, loc.longitude, currentAzimuth)
        tvPointB.text = "B ✓  %.1f°  %s".format(currentAzimuth, directionLabel(currentAzimuth))
        tvPointB.setTextColor(0xFF81C784.toInt())
        tvGuide.text = "계산 중..."
        calculateDistance()
    }

    private fun reset() {
        pointA = null
        pointB = null
        btnRecordB.isEnabled = false
        tvPointA.text = "A: 대기 중"
        tvPointB.text = "B: 대기 중"
        tvPointA.setTextColor(0xAAFFFFFF.toInt())
        tvPointB.setTextColor(0xAAFFFFFF.toInt())
        tvDistance.text = ""
        tvDistanceLabel.text = ""
        tvGuide.text = "목표물에 조준한 뒤 A 찍기를 누르세요"
    }

    // ── 삼각측량 계산 ─────────────────────────────────────────

    private fun calculateDistance() {
        val a = pointA ?: return
        val b = pointB ?: return

        // 로컬 좌표계 변환 (A를 원점으로, 단위: 미터)
        val metersPerLat = 110540.0
        val metersPerLon = 111320.0 * cos(Math.toRadians(a.lat))

        val bx = (b.lon - a.lon) * metersPerLon   // 동쪽(+) / 서쪽(-)
        val by = (b.lat - a.lat) * metersPerLat   // 북쪽(+) / 남쪽(-)

        val baseline = sqrt(bx * bx + by * by)

        // 베이스라인 경고
        if (baseline < 3.0) {
            showError("두 지점이 너무 가깝습니다 (${baseline.toInt()}m)\n10m 이상 이동 후 다시 시도하세요")
            return
        }

        val bearA = Math.toRadians(a.azimuth.toDouble())
        val bearB = Math.toRadians(b.azimuth.toDouble())

        val sinA = sin(bearA); val cosA = cos(bearA)
        val sinB = sin(bearB); val cosB = cos(bearB)

        // 행렬식: sin(β_B - β_A)
        val det = sinB * cosA - cosB * sinA
        val angleDiff = Math.toDegrees(abs(asin(det.coerceIn(-1.0, 1.0))))

        if (abs(det) < 0.087) {  // 5° 미만 각도차
            showError("두 방위각의 차이가 너무 작습니다 (${angleDiff.toInt()}°)\n다른 방향으로 이동 후 측정하세요")
            return
        }

        // t = 목표까지의 거리 (A 기준)
        val t = (-bx * cosB + by * sinB) / det

        if (t < 0) {
            showError("방향이 잘못됐습니다.\n카메라를 목표물에 정확히 겨냥했는지 확인하세요")
            return
        }

        val s = (-bx * cosA + by * sinA) / det
        if (s < 0) {
            showError("B 지점의 방향이 잘못됐습니다\n같은 목표물을 향해 다시 측정하세요")
            return
        }

        // 두 경로의 평균으로 정확도 향상
        val distFromA = t
        val distFromB = s
        // 교차점 좌표 (검증용)
        val px = bx + s * sinB
        val py = by + s * cosB
        val crossCheck = sqrt((px - t * sinA).pow(2) + (py - t * cosA).pow(2))

        val finalDist = (distFromA + distFromB) / 2.0  // A, B 평균

        showResult(distFromA, baseline, angleDiff, crossCheck)
    }

    private fun showResult(distM: Double, baselineM: Double, angleDiff: Double, errorM: Double) {
        runOnUiThread {
            tvDistance.text = if (distM >= 1000) {
                "%.2f km".format(distM / 1000.0)
            } else {
                "%.1f m".format(distM)
            }
            tvDistanceLabel.text = "베이스라인 ${baselineM.toInt()}m | 각도차 ${angleDiff.toInt()}°"
            tvGuide.text = "측정 완료 ✓"
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            tvDistance.text = "⚠"
            tvDistanceLabel.text = ""
            tvGuide.text = msg
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────

    private fun lowPassAngle(input: Float, prev: Float, alpha: Float = 0.15f): Float {
        var diff = input - prev
        if (diff > 180f)  diff -= 360f
        if (diff < -180f) diff += 360f
        var result = prev + alpha * diff
        if (result < 0f)    result += 360f
        if (result >= 360f) result -= 360f
        return result
    }

    private fun directionLabel(az: Float): String = when {
        az < 22.5  || az >= 337.5 -> "N"
        az < 67.5  -> "NE"
        az < 112.5 -> "E"
        az < 157.5 -> "SE"
        az < 202.5 -> "S"
        az < 247.5 -> "SW"
        az < 292.5 -> "W"
        else       -> "NW"
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
