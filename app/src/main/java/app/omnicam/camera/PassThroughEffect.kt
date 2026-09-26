// SPDX-License-Identifier: GPL-3.0-or-later
// OmniCam — privacy-first open-source camera. Original code; see NOTICE.

package app.omnicam.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * The leanest possible "effect": one OpenGL copy of each camera frame to every output surface.
 * Its purpose is not to draw anything but to make Preview and VideoCapture share ONE camera
 * stream (CameraX stream sharing), so the viewfinder shows exactly the frames being recorded.
 * Unlike OverlayEffect it has no overlay canvas, no frame queue and no per-frame allocations,
 * so it can keep up at 4K on older GPUs.
 */
class PassThroughEffect(
    targets: Int,
    processor: PassThroughProcessor,
    onError: Consumer<Throwable>,
) : CameraEffect(targets, processor.executor, processor, onError)

class PassThroughProcessor(private val onError: (Throwable) -> Unit) : SurfaceProcessor, AutoCloseable {

    private val thread = HandlerThread("omnicam-gl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { r -> handler.post(r) }

    // EGL / GL state — touched only on the GL thread
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var textureId = 0
    private var initialized = false
    private var closed = false

    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()

    private val vertices: FloatBuffer = floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
    ).toBuffer()
    private val texCoords: FloatBuffer = floatArrayOf(
        0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f,
    ).toBuffer()

    // ───────────────────────── SurfaceProcessor ─────────────────────────

    override fun onInputSurface(request: SurfaceRequest) {
        if (closed) { request.willNotProvideSurface(); return }
        try {
            ensureInit()
            releaseInput()
            val st = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
            }
            val surface = Surface(st)
            inputTexture = st
            inputSurface = surface
            st.setOnFrameAvailableListener({ drawFrame() }, handler)
            request.provideSurface(surface, executor) {
                // Camera is done with this surface: release it (a newer request may already be active)
                if (inputSurface === surface) releaseInput() else { surface.release(); st.release() }
            }
        } catch (e: Throwable) {
            request.willNotProvideSurface()
            onError(e)
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (closed) { surfaceOutput.close(); return }
        try {
            ensureInit()
            val surface = surfaceOutput.getSurface(executor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) removeOutput(surfaceOutput)
            }
            val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (egl == null || egl == EGL14.EGL_NO_SURFACE) error("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            outputs[surfaceOutput] = egl
        } catch (e: Throwable) {
            surfaceOutput.close()
            onError(e)
        }
    }

    override fun close() {
        handler.post {
            closed = true
            releaseInput()
            outputs.keys.toList().forEach { removeOutput(it) }
            if (initialized) {
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                initialized = false
            }
            thread.quitSafely()
        }
    }

    // ───────────────────────── rendering ─────────────────────────

    private fun drawFrame() {
        val st = inputTexture ?: return
        if (!initialized || closed) return
        try {
            // Always consume the frame, even with no outputs, so the camera's buffer queue keeps moving
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val ts = st.timestamp
            val it = outputs.entries.iterator()
            while (it.hasNext()) {
                val (out, egl) = it.next()
                if (!EGL14.eglMakeCurrent(display, egl, egl, context)) continue
                out.updateTransformMatrix(outMatrix, texMatrix)
                val size = out.size
                GLES20.glViewport(0, 0, size.width, size.height)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, outMatrix, 0)
                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
                GLES20.glEnableVertexAttribArray(aTexCoord)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPosition)
                GLES20.glDisableVertexAttribArray(aTexCoord)
                EGLExt.eglPresentationTimeANDROID(display, egl, ts)
                if (!EGL14.eglSwapBuffers(display, egl)) {
                    // Surface gone (e.g. recording stopped): drop this output
                    it.remove()
                    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                    EGL14.eglDestroySurface(display, egl)
                    out.close()
                }
            }
        } catch (e: Throwable) {
            onError(e)
        }
    }

    private fun removeOutput(out: SurfaceOutput) {
        val egl = outputs.remove(out)
        if (egl != null && initialized) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, egl)
        }
        out.close()
    }

    private fun releaseInput() {
        inputTexture?.setOnFrameAvailableListener(null)
        inputSurface?.release()
        inputTexture?.release()
        inputSurface = null
        inputTexture = null
    }

    // ───────────────────────── EGL / GL setup ─────────────────────────

    private fun ensureInit() {
        if (initialized) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "No EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        initialized = true
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: " + GLES20.glGetProgramInfoLog(p) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun FloatArray.toBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
            it.put(this); it.position(0)
        }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
