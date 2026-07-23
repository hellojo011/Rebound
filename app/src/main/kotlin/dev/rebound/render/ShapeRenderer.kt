package dev.rebound.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Draws anti-aliased rounded rectangles, circles, rings and thick lines.
 *
 * All four are the same primitive: a rotatable rounded box evaluated as a signed
 * distance field in the fragment shader, optionally with a disc punched out of
 * the middle. A circle is a box whose corner radius is half its size; a ring is
 * that with a hole; a line is a long thin box turned to face its endpoints.
 *
 * One shader covers the entire game, so the project ships no image assets and
 * every edge stays sharp at any resolution.
 */
class ShapeRenderer {

    private var program = 0
    private var aPosition = 0
    private var uViewport = 0
    private var uCenterPx = 0
    private var uSizePx = 0
    private var uRotation = 0
    private var uHalfSizePx = 0
    private var uRadiusPx = 0
    private var uInnerPx = 0
    private var uColor = 0

    private var viewportWidth = 1f
    private var viewportHeight = 1f

    private lateinit var quad: FloatBuffer

    fun init() {
        val vertices = floatArrayOf(
            -0.5f, -0.5f,
            0.5f, -0.5f,
            -0.5f, 0.5f,
            0.5f, 0.5f,
        )
        quad = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(vertices); position(0) }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        uViewport = GLES20.glGetUniformLocation(program, "uViewport")
        uCenterPx = GLES20.glGetUniformLocation(program, "uCenterPx")
        uSizePx = GLES20.glGetUniformLocation(program, "uSizePx")
        uRotation = GLES20.glGetUniformLocation(program, "uRotation")
        uHalfSizePx = GLES20.glGetUniformLocation(program, "uHalfSizePx")
        uRadiusPx = GLES20.glGetUniformLocation(program, "uRadiusPx")
        uInnerPx = GLES20.glGetUniformLocation(program, "uInnerPx")
        uColor = GLES20.glGetUniformLocation(program, "uColor")

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun resize(width: Int, height: Int) {
        viewportWidth = width.toFloat()
        viewportHeight = height.toFloat()
        GLES20.glViewport(0, 0, width, height)
    }

    fun beginFrame() {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform2f(uViewport, viewportWidth, viewportHeight)
    }

    fun endFrame() {
        GLES20.glDisableVertexAttribArray(aPosition)
    }

    fun clear(color: Int) {
        GLES20.glClearColor(color.red(), color.green(), color.blue(), 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * @param cx,cy centre in pixels, origin top-left.
     * @param radiusPx corner radius, clamped to half the smaller side.
     * @param innerPx radius of a disc removed from the centre; 0 for a solid shape.
     * @param rotation radians, clockwise on screen.
     */
    fun roundRect(
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        radiusPx: Float = 0f,
        color: Int,
        alpha: Float = 1f,
        rotation: Float = 0f,
        innerPx: Float = 0f,
    ) {
        if (width <= 0f || height <= 0f || alpha <= 0f) return

        val halfW = width * 0.5f
        val halfH = height * 0.5f
        // The quad is expanded slightly so the SDF has room to feather its edge.
        val pad = 2f

        GLES20.glUniform2f(uCenterPx, cx, cy)
        GLES20.glUniform2f(uSizePx, width + pad * 2f, height + pad * 2f)
        GLES20.glUniform1f(uRotation, rotation)
        GLES20.glUniform2f(uHalfSizePx, halfW, halfH)
        GLES20.glUniform1f(uRadiusPx, radiusPx.coerceIn(0f, minOf(halfW, halfH)))
        GLES20.glUniform1f(uInnerPx, innerPx)
        GLES20.glUniform4f(uColor, color.red(), color.green(), color.blue(), alpha)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun rect(cx: Float, cy: Float, width: Float, height: Float, color: Int, alpha: Float = 1f) =
        roundRect(cx, cy, width, height, 0f, color, alpha)

    fun circle(cx: Float, cy: Float, diameter: Float, color: Int, alpha: Float = 1f) =
        roundRect(cx, cy, diameter, diameter, diameter * 0.5f, color, alpha)

    /** A circle with its middle removed. [thickness] is the width of the band. */
    fun ring(
        cx: Float,
        cy: Float,
        diameter: Float,
        thickness: Float,
        color: Int,
        alpha: Float = 1f,
    ) = roundRect(
        cx = cx,
        cy = cy,
        width = diameter,
        height = diameter,
        radiusPx = diameter * 0.5f,
        color = color,
        alpha = alpha,
        innerPx = (diameter * 0.5f - thickness).coerceAtLeast(0f),
    )

    /** A capsule spanning two points. */
    fun line(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        thickness: Float,
        color: Int,
        alpha: Float = 1f,
        rounded: Boolean = true,
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = hypot(dx, dy)
        if (length < 0.01f) return
        roundRect(
            cx = (x1 + x2) * 0.5f,
            cy = (y1 + y2) * 0.5f,
            width = length,
            height = thickness,
            radiusPx = if (rounded) thickness * 0.5f else 0f,
            color = color,
            alpha = alpha,
            rotation = atan2(dy, dx),
        )
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)

        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "program link failed: ${GLES20.glGetProgramInfoLog(id)}" }

        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(id)}" }
        return id
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            uniform vec2 uViewport;
            uniform vec2 uCenterPx;
            uniform vec2 uSizePx;
            uniform float uRotation;
            varying vec2 vLocalPx;
            void main() {
                // Lay the quad out in pixels so rotation is not skewed by the
                // viewport's aspect ratio, then convert to clip space.
                vec2 p = aPosition * uSizePx;
                float c = cos(uRotation);
                float s = sin(uRotation);
                vec2 r = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
                vLocalPx = p;
                vec2 px = uCenterPx + r;
                gl_Position = vec4(
                    px.x / uViewport.x * 2.0 - 1.0,
                    1.0 - px.y / uViewport.y * 2.0,
                    0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vLocalPx;
            uniform vec2 uHalfSizePx;
            uniform float uRadiusPx;
            uniform float uInnerPx;
            uniform vec4 uColor;
            void main() {
                // Signed distance to a rounded box, in pixels.
                vec2 p = abs(vLocalPx);
                vec2 d = p - (uHalfSizePx - vec2(uRadiusPx));
                float dist = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - uRadiusPx;
                float alpha = 1.0 - smoothstep(-1.0, 1.0, dist);

                // Punch out the middle to turn a disc into a ring.
                if (uInnerPx > 0.0) {
                    float inner = length(vLocalPx) - uInnerPx;
                    alpha *= smoothstep(-1.0, 1.0, inner);
                }

                gl_FragColor = vec4(uColor.rgb, uColor.a * alpha);
            }
        """
    }
}

internal fun Int.red() = ((this shr 16) and 0xFF) / 255f
internal fun Int.green() = ((this shr 8) and 0xFF) / 255f
internal fun Int.blue() = (this and 0xFF) / 255f
