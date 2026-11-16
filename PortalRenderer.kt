package com.example.portalvr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Desenha tudo em OpenGL, duas vezes (olho esquerdo e direito).
 *
 * Estados:
 *  NORMAL   - só a câmera
 *  PORTAL_A - portal aberto sobre a câmera (mostra "outro mundo")
 *  WORLD    - imagem 360 cobrindo tudo (segue o giroscópio)
 *  PORTAL_B - portal aberto sobre o 360 (mostra a câmera dentro)
 */
class PortalRenderer(
    private val context: Context,
    private val onSurfaceReady: () -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        const val NORMAL = 0
        const val PORTAL_A = 1
        const val WORLD = 2
        const val PORTAL_B = 3
        private const val BASE_R = 0.30f
        private const val TAG = "PortalVR"

        private const val VS = """
attribute vec2 aPos;
varying vec2 vPos;
void main() {
    vPos = aPos;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val FS = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vPos;
uniform samplerExternalOES uCam;
uniform sampler2D uWorld;
uniform mat4 uTexMat;
uniform vec2 uCamK;
uniform float uCamRot;
uniform vec2 uFlip;
uniform float uAspect;
uniform float uBgMode;
uniform float uPortalOn;
uniform vec2 uPC;
uniform float uPR;
uniform float uOpen;
uniform float uGrow;
uniform float uContent;
uniform float uTime;
uniform mat3 uRot;
uniform float uYaw;
uniform vec2 uTan;
uniform float uK;
uniform vec3 uCursor;
uniform float uPinch;

const float PI = 3.14159265;

vec3 camColor(vec2 s) {
    vec2 c = vec2(0.5) + (s - vec2(0.5)) * uCamK;
    if (uCamRot > 0.5) c = vec2(1.0) - c;
    if (uFlip.x > 0.5) c.x = 1.0 - c.x;
    if (uFlip.y > 0.5) c.y = 1.0 - c.y;
    vec2 t = vec2(c.x, 1.0 - c.y);
    vec2 tc = (uTexMat * vec4(t, 0.0, 1.0)).xy;
    return texture2D(uCam, tc).rgb;
}

vec3 worldUV(vec2 uv) {
    vec2 q = vec2(fract(uv.x), clamp(uv.y, 0.002, 0.998));
    return texture2D(uWorld, q).rgb;
}

vec3 world360(vec2 s) {
    vec2 n = vec2(s.x * 2.0 - 1.0, 1.0 - s.y * 2.0);
    vec3 ray = normalize(vec3(n.x * uTan.x, n.y * uTan.y, -1.0));
    vec3 w = uRot * ray;
    float yaw = atan(w.x, w.y) - uYaw;
    float pitch = asin(clamp(w.z, -1.0, 1.0));
    return worldUV(vec2(0.5 + yaw / (2.0 * PI), 0.5 - pitch / PI));
}

vec3 background(vec2 s) {
    if (uBgMode > 0.5) return world360(s);
    return camColor(s);
}

vec2 rot2(vec2 v, float a) {
    float c = cos(a);
    float sn = sin(a);
    return vec2(c * v.x - sn * v.y, sn * v.x + c * v.y);
}

vec3 content(vec2 s, vec2 q, float r01) {
    float twist = r01 * r01 * (1.6 + 0.4 * sin(uTime * 1.3));
    vec2 qr = rot2(q, twist);
    vec3 inner;
    if (uContent < 0.5) {
        vec2 uv = vec2(0.5 + qr.x * 0.12 + uTime * 0.004, 0.5 - qr.y * 0.24);
        inner = worldUV(uv);
        if (uGrow > 0.0) inner = mix(inner, world360(s), uGrow);
    } else {
        vec2 sIn = vec2(0.5) + vec2(qr.x / uAspect, qr.y) * 0.32;
        sIn = mix(sIn, s, uGrow);
        inner = camColor(sIn);
    }
    return inner;
}

void main() {
    vec2 p = vPos;
    float r2 = dot(p, p);
    vec2 pd = p * (1.0 + uK * r2);
    if (abs(pd.x) > 1.0 || abs(pd.y) > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 s = vec2(pd.x * 0.5 + 0.5, 0.5 - pd.y * 0.5);

    vec3 col;
    if (uPortalOn > 0.5 && uPR > 0.002) {
        vec2 d = s - uPC;
        d.x *= uAspect;
        float r = length(d);
        float R = uPR;
        float w = 1.0 - smoothstep(R, R * 1.9, r);
        float ang = w * w * (1.4 + 0.5 * sin(uTime * 1.7)) * uOpen;
        vec2 dw = rot2(d, ang) * (1.0 - 0.4 * w * uOpen);
        vec2 sw = uPC + vec2(dw.x / uAspect, dw.y);
        vec3 bg = background(sw);

        float r01 = clamp(r / R, 0.0, 1.0);
        vec2 q = d / R;
        vec3 inner = content(s, q, r01);
        float inside = 1.0 - smoothstep(R * 0.96, R, r);
        col = mix(bg, inner, inside);

        float e = (r - R) / (0.03 + 0.05 * R);
        float ring = exp(-e * e);
        float a = atan(d.y, d.x);
        float n = 0.5 + 0.5 * sin(a * 9.0 + uTime * 5.0 + r * 30.0);
        vec3 glowCol = mix(vec3(0.25, 0.6, 1.0), vec3(0.8, 0.4, 1.0), 0.5 + 0.5 * sin(a * 3.0 - uTime * 2.0));
        col += glowCol * ring * (0.8 + 0.9 * n) * uOpen;
        col += glowCol * 0.25 * exp(-max(r - R, 0.0) * 6.0) * (1.0 - inside) * uOpen;
    } else {
        col = background(s);
    }

    if (uCursor.z > 0.5) {
        vec2 dc = s - uCursor.xy;
        dc.x *= uAspect;
        float dd = length(dc);
        float outer = 1.0 - smoothstep(0.020, 0.024, dd);
        float innerC = 1.0 - smoothstep(0.011, 0.015, dd);
        vec3 cc = mix(vec3(1.0), vec3(0.2, 1.0, 0.4), uPinch);
        col = mix(col, cc, clamp(outer - innerC * (1.0 - uPinch), 0.0, 1.0) * 0.9);
    }

    gl_FragColor = vec4(col, 1.0);
}
"""
    }

    @Volatile var surfaceTexture: SurfaceTexture? = null
    @Volatile var camRotation180 = false
    @Volatile var camFlip = 1
    @Volatile var camAspect = 4f / 3f
    @Volatile var distortion = 0.18f
    @Volatile var rotM = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    @Volatile private var newFrame = false
    private var program = 0
    private var camTex = 0
    private var worldTex = 0
    private var viewW = 1
    private var viewH = 1
    private val texMat = FloatArray(16)
    private val rotT = FloatArray(9)
    private val locs = HashMap<String, Int>()
    private lateinit var quad: FloatBuffer
    private val t0 = SystemClock.uptimeMillis()

    // Estado (usado só na thread do OpenGL)
    private var state = NORMAL
    private var portalStart = 0f
    private var growStart = -1f
    private var pcx = 0.5f
    private var pcy = 0.5f
    private var yaw = 0f
    private var curVis = false
    private var curX = 0f
    private var curY = 0f
    private var curPinch = false
    private val tmp = FloatArray(2)

    private fun nowSec(): Float = (SystemClock.uptimeMillis() - t0) / 1000f

    // ------------------------------------------------------------------
    // Eventos vindos das mãos (chamados na thread do OpenGL)
    // ------------------------------------------------------------------

    private fun eyeAspect(): Float = (viewW / 2f) / viewH.toFloat()

    private fun toScene(cu: Float, cv: Float) {
        val a = eyeAspect()
        val c = camAspect
        val kx = min(1f, a / c)
        val ky = min(1f, c / a)
        tmp[0] = 0.5f + (cu - 0.5f) / kx
        tmp[1] = 0.5f + (cv - 0.5f) / ky
    }

    fun onHand(visible: Boolean, cu: Float, cv: Float, pinching: Boolean) {
        curVis = visible
        if (visible) {
            toScene(cu, cv)
            curX = tmp[0]
            curY = tmp[1]
        }
        curPinch = pinching
    }

    fun onSwipe(cu: Float) {
        if (state != NORMAL && state != WORLD) return
        toScene(cu, 0.5f)
        pcx = tmp[0].coerceIn(0.3f, 0.7f)
        pcy = 0.5f
        portalStart = nowSec()
        growStart = -1f
        state = if (state == NORMAL) PORTAL_A else PORTAL_B
    }

    fun onPinchClick(cu: Float, cv: Float) {
        if (state != PORTAL_A && state != PORTAL_B) return
        if (growStart >= 0f) return
        val t = nowSec()
        if (openAmount(t) < 0.6f) return
        toScene(cu, cv)
        val dx = (tmp[0] - pcx) * eyeAspect()
        val dy = tmp[1] - pcy
        if (hypot(dx, dy) < BASE_R * 1.3f) {
            growStart = t
            if (state == PORTAL_A) {
                val r = rotM
                yaw = atan2(-r[2], -r[5])
            }
        }
    }

    private fun openAmount(t: Float): Float {
        if (state != PORTAL_A && state != PORTAL_B) return 0f
        val x = ((t - portalStart) / 1.1f).coerceIn(0f, 1f)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val y = x - 1f
        return 1f + c3 * y * y * y + c1 * y * y
    }

    private fun growAmount(t: Float): Float {
        if (growStart < 0f) return 0f
        val x = ((t - growStart) / 0.9f).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    // ------------------------------------------------------------------
    // OpenGL
    // ------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            surfaceTexture?.release()
        } catch (e: Exception) {
        }
        surfaceTexture = null
        locs.clear()

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram()

        val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        quad = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(v)
        quad.position(0)

        val tex = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        camTex = tex[0]
        worldTex = tex[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val bmp = loadWorldBitmap()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, worldTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()

        Matrix.setIdentityM(texMat, 0)
        val st = SurfaceTexture(camTex)
        st.setOnFrameAvailableListener { newFrame = true }
        surfaceTexture = st

        onSurfaceReady()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    private fun u(name: String): Int {
        var l = locs[name]
        if (l == null) {
            l = GLES20.glGetUniformLocation(program, name)
            locs[name] = l
        }
        return l
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture
        if (newFrame && st != null) {
            newFrame = false
            try {
                st.updateTexImage()
                st.getTransformMatrix(texMat)
            } catch (e: Exception) {
                Log.e(TAG, "updateTexImage", e)
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val t = nowSec()
        var g = growAmount(t)
        if (growStart >= 0f && g >= 1f) {
            state = if (state == PORTAL_A) WORLD else NORMAL
            growStart = -1f
            g = 0f
        }
        val portalOn = state == PORTAL_A || state == PORTAL_B
        val open = openAmount(t)
        val radius = BASE_R * open * (1f + 6f * g)
        val bgMode = if (state == WORLD || state == PORTAL_B) 1f else 0f
        val content = if (state == PORTAL_B) 1f else 0f

        val r = rotM
        for (c in 0..2) {
            for (row in 0..2) {
                rotT[c * 3 + row] = r[row * 3 + c]
            }
        }

        val aspect = eyeAspect()
        val c = camAspect
        val kx = min(1f, aspect / c)
        val ky = min(1f, c / aspect)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, worldTex)

        GLES20.glUniform1i(u("uCam"), 0)
        GLES20.glUniform1i(u("uWorld"), 1)
        GLES20.glUniformMatrix4fv(u("uTexMat"), 1, false, texMat, 0)
        GLES20.glUniform2f(u("uCamK"), kx, ky)
        GLES20.glUniform1f(u("uCamRot"), if (camRotation180) 1f else 0f)
        GLES20.glUniform2f(
            u("uFlip"),
            if (camFlip == 1 || camFlip == 3) 1f else 0f,
            if (camFlip == 1 || camFlip == 2) 1f else 0f
        )
        GLES20.glUniform1f(u("uAspect"), aspect)
        GLES20.glUniform1f(u("uBgMode"), bgMode)
        GLES20.glUniform1f(u("uPortalOn"), if (portalOn) 1f else 0f)
        GLES20.glUniform2f(u("uPC"), pcx, pcy)
        GLES20.glUniform1f(u("uPR"), radius)
        GLES20.glUniform1f(u("uOpen"), open)
        GLES20.glUniform1f(u("uGrow"), g)
        GLES20.glUniform1f(u("uContent"), content)
        GLES20.glUniform1f(u("uTime"), t)
        GLES20.glUniformMatrix3fv(u("uRot"), 1, false, rotT, 0)
        GLES20.glUniform1f(u("uYaw"), yaw)
        GLES20.glUniform2f(u("uTan"), 1f, 1f / aspect)
        GLES20.glUniform1f(u("uK"), distortion)
        GLES20.glUniform3f(u("uCursor"), curX, curY, if (curVis) 1f else 0f)
        GLES20.glUniform1f(u("uPinch"), if (curPinch) 1f else 0f)

        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        quad.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quad)

        val half = viewW / 2
        for (eye in 0..1) {
            GLES20.glViewport(eye * half, 0, half, viewH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun buildProgram(): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, VS))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, FS))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    // ------------------------------------------------------------------
    // Imagem do "outro mundo" / 360
    // ------------------------------------------------------------------

    private fun loadWorldBitmap(): Bitmap {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            context.assets.open("mundo360.jpg").use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 4096) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = context.assets.open("mundo360.jpg").use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) return bmp
        } catch (e: Exception) {
            // sem mundo360.jpg: usa a imagem gerada pelo app
        }
        return generateWorld()
    }

    private fun generateWorld(): Bitmap {
        val w = 2048
        val h = 1024
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(7)
        val wf = w.toFloat()
        val horizon = h * 0.60f

        // céu
        p.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(0xFF03000C.toInt(), 0xFF1E0845.toInt(), 0xFF8E2A8C.toInt(), 0xFFFF8A4C.toInt()),
            floatArrayOf(0f, 0.4f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, horizon, p)
        p.shader = null

        // nebulosas (desenhadas 3x para fechar a costura horizontal)
        val nebColors = intArrayOf(
            Color.argb(90, 80, 200, 255), Color.argb(90, 200, 80, 255),
            Color.argb(80, 255, 100, 160), Color.argb(80, 60, 255, 190)
        )
        for (i in 0 until 7) {
            val cx = rnd.nextFloat() * wf
            val cy = rnd.nextFloat() * horizon * 0.8f
            val rad = 200f + rnd.nextFloat() * 250f
            val col = nebColors[i % nebColors.size]
            val clear = col and 0x00FFFFFF
            for (off in intArrayOf(-w, 0, w)) {
                p.shader = RadialGradient(cx + off, cy, rad, col, clear, Shader.TileMode.CLAMP)
                c.drawCircle(cx + off, cy, rad, p)
            }
        }
        p.shader = null

        // estrelas
        for (i in 0 until 900) {
            val x = rnd.nextFloat() * wf
            val y = rnd.nextFloat() * horizon * 0.95f
            p.color = Color.argb(120 + rnd.nextInt(135), 255, 255, 255)
            c.drawCircle(x, y, 0.6f + rnd.nextFloat() * 1.6f, p)
        }

        // planeta com anel (bem na frente de onde você entra)
        val px = wf * 0.5f
        val py = h * 0.34f
        p.shader = RadialGradient(
            px - 40f, py - 40f, 190f,
            intArrayOf(0xFFBFF3FF.toInt(), 0xFF3C7BD9.toInt(), 0xFF0B1B4D.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(px, py, 140f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 14f
        p.color = Color.argb(170, 255, 230, 190)
        c.save()
        c.rotate(-16f, px, py)
        c.drawOval(RectF(px - 290f, py - 55f, px + 290f, py + 55f), p)
        c.restore()
        p.style = Paint.Style.FILL

        // luas
        val moons = arrayOf(
            floatArrayOf(wf * 0.63f, h * 0.27f, 50f),
            floatArrayOf(wf * 0.38f, h * 0.40f, 28f),
            floatArrayOf(wf * 0.82f, h * 0.22f, 36f)
        )
        for (m in moons) {
            p.shader = RadialGradient(
                m[0] - m[2] * 0.3f, m[1] - m[2] * 0.3f, m[2] * 1.4f,
                intArrayOf(0xFFFFE2B0.toInt(), 0xFFFF8A3D.toInt(), 0xFF6B2A10.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(m[0], m[1], m[2], p)
        }
        p.shader = null

        // sol atrás de você (na costura da imagem)
        for (off in intArrayOf(0, w)) {
            p.shader = RadialGradient(
                off.toFloat(), h * 0.47f, 420f,
                Color.argb(200, 255, 220, 140), Color.argb(0, 255, 160, 60), Shader.TileMode.CLAMP
            )
            c.drawCircle(off.toFloat(), h * 0.47f, 420f, p)
            p.shader = null
            p.color = Color.rgb(255, 245, 210)
            c.drawCircle(off.toFloat(), h * 0.47f, 70f, p)
        }

        // chão
        p.shader = LinearGradient(
            0f, horizon, 0f, h.toFloat(),
            intArrayOf(0xFF3A1250.toInt(), 0xFF12051F.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, horizon, wf, h.toFloat(), p)
        p.shader = null

        // reflexos na água
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        for (i in 0 until 160) {
            val f = rnd.nextFloat()
            val y = horizon + f * f * (h - horizon)
            val x = rnd.nextFloat() * wf
            val len = 10f + rnd.nextFloat() * 90f * (0.3f + f)
            p.color = Color.argb(40 + rnd.nextInt(80), 255, 150, 220)
            c.drawLine(x, y, x + len, y, p)
        }
        p.style = Paint.Style.FILL

        // montanhas (periódicas, para não ter costura)
        val layers = arrayOf(
            doubleArrayOf(0xFF2A0F44.toInt().toDouble(), 70.0, 34.0, 20.0, 11.0, 1.3),
            doubleArrayOf(0xFF0B0416.toInt().toDouble(), 40.0, 28.0, 18.0, 9.0, 0.0)
        )
        for (ly in layers) {
            val path = Path()
            path.moveTo(0f, horizon + 6f)
            var x = 0
            while (x <= w) {
                val a = x * 2.0 * PI / w
                val hh = ly[1] + ly[2] * sin(3 * a + 0.8 + ly[5]) +
                    ly[3] * sin(7 * a + 2.1 + ly[5]) + ly[4] * sin(17 * a + 4.0 + ly[5])
                path.lineTo(x.toFloat(), horizon - hh.toFloat())
                x += 8
            }
            path.lineTo(wf, horizon + 6f)
            path.close()
            p.color = ly[0].toInt()
            c.drawPath(path, p)
        }

        return bmp
    }
}
