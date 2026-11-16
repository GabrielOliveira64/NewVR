package com.example.portalvr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.hypot

/**
 * Reconhece a mão (MediaPipe) e detecta dois gestos:
 *  - "paz" (indicador + médio para cima) varrendo de cima para baixo  -> onSwipe
 *  - pinça (polegar + indicador)                                       -> onPinchClick
 * Todas as coordenadas são normalizadas (0..1) na imagem da câmera já "em pé".
 */
class HandTracker(context: Context, private val listener: Listener) {

    interface Listener {
        fun onHand(visible: Boolean, x: Float, y: Float, pinching: Boolean)
        fun onSwipe(x: Float)
        fun onPinchClick(x: Float, y: Float)
    }

    private class Sample(val t: Long, val x: Float, val y: Float)

    private var landmarker: HandLandmarker? = null
    val ready: Boolean get() = landmarker != null

    private var lastTs = 0L
    private var pinched = false
    private val hist = ArrayList<Sample>()
    private var lastPeace = 0L
    private var cooldownUntil = 0L

    init {
        try {
            val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setNumHands(1)
                .setRunningMode(RunningMode.VIDEO)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            landmarker = HandLandmarker.createFromOptions(context, opts)
        } catch (e: Exception) {
            Log.e("PortalVR", "Falha ao carregar o modelo de mãos", e)
        }
    }

    fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val src = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val bmp: Bitmap = if (rot != 0) {
                val m = Matrix()
                m.postRotate(rot.toFloat())
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            } else src
            val aspect = bmp.width.toFloat() / bmp.height.toFloat()

            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts

            val result = lm.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            process(result.landmarks(), aspect, ts)
        } catch (e: Exception) {
            Log.e("PortalVR", "Erro na análise", e)
        } finally {
            proxy.close()
        }
    }

    private fun d(a: NormalizedLandmark, b: NormalizedLandmark, asp: Float): Float =
        hypot((a.x() - b.x()) * asp, a.y() - b.y())

    private fun process(all: List<List<NormalizedLandmark>>, asp: Float, now: Long) {
        if (all.isEmpty()) {
            pinched = false
            if (now - lastPeace > 300) hist.clear()
            listener.onHand(false, 0f, 0f, false)
            return
        }
        val l = all[0]
        val wrist = l[0]

        // ---- pinça ----
        val palm = d(wrist, l[9], asp).coerceAtLeast(0.0001f)
        val pinchRatio = d(l[4], l[8], asp) / palm
        val mx = (l[4].x() + l[8].x()) / 2f
        val my = (l[4].y() + l[8].y()) / 2f
        if (!pinched && pinchRatio < 0.28f) {
            pinched = true
            listener.onPinchClick(mx, my)
        } else if (pinched && pinchRatio > 0.5f) {
            pinched = false
        }
        listener.onHand(true, mx, my, pinched)

        // ---- gesto de paz (indicador + médio retos, anelar + mindinho recolhidos) ----
        fun ext(tip: Int, mcp: Int): Float =
            d(l[tip], wrist, asp) / d(l[mcp], wrist, asp).coerceAtLeast(0.0001f)

        val peace = ext(8, 5) > 1.6f && ext(12, 9) > 1.6f &&
            ext(16, 13) < 1.45f && ext(20, 17) < 1.45f
        val dxUp = (l[12].x() - wrist.x()) * asp
        val dyUp = l[12].y() - wrist.y()
        val pointingUp = -dyUp > 0.35f * hypot(dxUp, dyUp)

        if (peace && pointingUp) {
            lastPeace = now
            hist.add(Sample(now, l[9].x(), l[9].y()))
            while (hist.isNotEmpty() && now - hist[0].t > 800) hist.removeAt(0)

            if (now > cooldownUntil && hist.size >= 3) {
                var minY = Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                var sumX = 0f
                for (s in hist) {
                    if (s.y < minY) minY = s.y
                    if (s.y > maxY) maxY = s.y
                    sumX += s.x
                }
                val last = hist[hist.size - 1]
                val span = last.t - hist[0].t
                if (last.y - minY > 0.2f && last.y >= maxY - 0.03f && span >= 100) {
                    cooldownUntil = now + 2000
                    val meanX = sumX / hist.size
                    hist.clear()
                    listener.onSwipe(meanX)
                }
            }
        } else if (now - lastPeace > 300) {
            hist.clear()
        }
    }
}
