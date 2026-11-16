package com.example.portalvr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors

class PortalActivity : ComponentActivity(), SensorEventListener, HandTracker.Listener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: PortalRenderer
    private lateinit var sensorManager: SensorManager
    private var rotSensor: Sensor? = null
    private var tracker: HandTracker? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var glReady = false
    private var permOk = false
    private val inR = FloatArray(9)
    private val outR = FloatArray(9)
    private val prefs by lazy { getSharedPreferences("portal_vr", Context.MODE_PRIVATE) }
    private var distortion = 0.18f
    private var camFlip = 1
    private var shownRotInfo = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) {
                permOk = true
                tryStartCamera()
            } else {
                Toast.makeText(this, "Preciso da permissão da câmera", Toast.LENGTH_LONG).show()
            }
        }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = windowManager.defaultDisplay.rotation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor == null) {
            Toast.makeText(this, "Sem sensor de rotação: o 360 não vai acompanhar a cabeça", Toast.LENGTH_LONG).show()
        }

        distortion = prefs.getFloat("k", 0.18f)
        renderer = PortalRenderer(this) {
            runOnUiThread {
                glReady = true
                tryStartCamera()
            }
        }
        renderer.distortion = distortion
        camFlip = prefs.getInt("quarter", 1)
        renderer.camFlip = camFlip

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setContentView(glView)

        val tr = HandTracker(this, this)
        tracker = tr
        if (!tr.ready) {
            Toast.makeText(this, "Modelo de mãos não carregou", Toast.LENGTH_LONG).show()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            permOk = true
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun tryStartCamera() {
        if (!glReady || !permOk) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val rotation = currentRotation()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()
            preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request ->
                provideSurface(request)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val t = tracker
                if (t != null) t.analyze(proxy) else proxy.close()
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun provideSurface(request: SurfaceRequest) {
        val st = renderer.surfaceTexture
        if (st == null) {
            request.willNotProvideSurface()
            return
        }
        val res = request.resolution
        st.setDefaultBufferSize(res.width, res.height)
        renderer.camAspect = res.width.toFloat() / res.height.toFloat()
        val surface = Surface(st)
        val exec = ContextCompat.getMainExecutor(this)
        request.setTransformationInfoListener(exec) { info ->
            renderer.camRotation180 = info.rotationDegrees == 180
            if (!shownRotInfo) {
                shownRotInfo = true
                Toast.makeText(this, "Rotação informada pelo CameraX: " + info.rotationDegrees, Toast.LENGTH_LONG).show()
            }
        }
        request.provideSurface(surface, exec) { surface.release() }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        glReady = false
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                future.get().unbindAll()
            } catch (e: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
        glView.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = if (event.values.size > 4) event.values.copyOf(4) else event.values
        SensorManager.getRotationMatrixFromVector(inR, v)
        val ax: Int
        val ay: Int
        when (currentRotation()) {
            Surface.ROTATION_90 -> {
                ax = SensorManager.AXIS_Y
                ay = SensorManager.AXIS_MINUS_X
            }
            Surface.ROTATION_270 -> {
                ax = SensorManager.AXIS_MINUS_Y
                ay = SensorManager.AXIS_X
            }
            Surface.ROTATION_180 -> {
                ax = SensorManager.AXIS_MINUS_X
                ay = SensorManager.AXIS_MINUS_Y
            }
            else -> {
                ax = SensorManager.AXIS_X
                ay = SensorManager.AXIS_Y
            }
        }
        SensorManager.remapCoordinateSystem(inR, ax, ay, outR)
        renderer.rotM = outR.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Botões de volume ajustam a correção das lentes do VR Box
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val step = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.03f else -0.03f
            distortion = (distortion + step).coerceIn(0f, 0.6f)
            renderer.distortion = distortion
            prefs.edit().putFloat("k", distortion).apply()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Tocar na tela troca a orientação da câmera (1 a 4) até ficar certa
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            camFlip = (camFlip + 1) % 4
            renderer.camFlip = camFlip
            prefs.edit().putInt("quarter", camFlip).apply()
            Toast.makeText(this, "Orientação da câmera: " + (camFlip + 1) + " de 4", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    // ---- gestos (chamados na thread de análise) ----
    override fun onHand(visible: Boolean, x: Float, y: Float, pinching: Boolean) {
        glView.queueEvent { renderer.onHand(visible, x, y, pinching) }
    }

    override fun onSwipe(x: Float) {
        glView.queueEvent { renderer.onSwipe(x) }
    }

    override fun onPinchClick(x: Float, y: Float) {
        glView.queueEvent { renderer.onPinchClick(x, y) }
    }
}
