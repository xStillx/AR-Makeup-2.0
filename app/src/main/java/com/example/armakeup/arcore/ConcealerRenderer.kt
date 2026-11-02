package com.example.armakeup.arcore

import android.opengl.GLES20
import com.example.armakeup.makeup.ConcealerStyle
import com.example.armakeup.makeup.FoundationStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.roundToInt

/** GL-thread-only GPU layer. Neither input camera frames nor their geometry are mutated. */
internal class ConcealerRenderer(private val createProgram: (String, String) -> Int) {
    private data class Target(val texture: Int, val framebuffer: Int, val depth: Int = 0)
    private var soft: Target? = null
    private var output: Target? = null
    private var width = 0
    private var height = 0
    private var softWidth = 0
    private var softHeight = 0
    private var copyProgram = 0
    private var softProgram = 0
    private var materialProgram = 0
    private var depthProgram = 0
    private var renderedMesh: FoundationFaceMesh? = null
    private var renderedStyle: ConcealerStyle? = null
    private var renderedBase = 0
    private var renderedBaseStyle: FoundationStyle? = null
    private var renderedRevision = -1L
    private var renderedCamera = 0
    private val quad = floats(8).apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)); position(0) }
    private var vertices = floats(0)
    private var indices: ShortBuffer = ByteBuffer.allocateDirect(0).asShortBuffer()

    fun onContextCreated() {
        soft = null; output = null; width = 0; height = 0
        // Compile lazily. Disabled concealer uses no additional GPU programs or targets.
        copyProgram = 0; softProgram = 0; materialProgram = 0; depthProgram = 0
        renderedMesh = null; renderedStyle = null; renderedCamera = 0; renderedBase = 0
        renderedBaseStyle = null
    }

    fun releaseTargets() {
        for (target in listOfNotNull(soft, output)) {
            GLES20.glDeleteTextures(1, intArrayOf(target.texture), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
            if (target.depth != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(target.depth), 0)
        }
        soft = null; output = null; width = 0; height = 0
        renderedMesh = null; renderedStyle = null; renderedCamera = 0; renderedBase = 0
        renderedBaseStyle = null
    }

    fun render(chart: ConcealerFaceMesh, camera: Int, baseCamera: Int, viewportWidth: Int, viewportHeight: Int,
        style: ConcealerStyle, baseStyle: FoundationStyle?, smoothing: SkinRetouchRenderer.Frame?): Int {
        val mesh = chart.face
        if (output != null && width == viewportWidth && height == viewportHeight &&
            renderedMesh === mesh && renderedStyle == style && renderedCamera == camera &&
            renderedBase == baseCamera && renderedBaseStyle == baseStyle && renderedRevision == (smoothing?.revision ?: -1L)) return output!!.texture
        if (materialProgram == 0) {
            copyProgram = createProgram(FoundationMaterial.QUAD_VERTEX, FoundationMaterial.COPY_FRAGMENT)
            softProgram = createProgram(FoundationMaterial.QUAD_VERTEX, FoundationMaterial.SOFT_FRAGMENT)
            materialProgram = createProgram(FoundationMaterial.VERTEX, ConcealerMaterial.FRAGMENT)
            depthProgram = createProgram(BlushMaterial.DEPTH_VERTEX_SHADER, BlushMaterial.DEPTH_FRAGMENT_SHADER)
        }
        if (width != viewportWidth || height != viewportHeight || output == null) {
            releaseTargets()
            width = viewportWidth; height = viewportHeight
            val scale = minOf(1f, 384f / maxOf(width, height))
            softWidth = (width * scale).roundToInt().coerceAtLeast(1)
            softHeight = (height * scale).roundToInt().coerceAtLeast(1)
            soft = createTarget(softWidth, softHeight, false)
            output = createTarget(width, height, true)
        }
        try {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, soft!!.framebuffer)
            GLES20.glViewport(0, 0, softWidth, softHeight)
            GLES20.glUseProgram(softProgram)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(softProgram, "uRadius"),
                mesh.sampleRadius, mesh.sampleRadius * width / height)
            drawQuad(softProgram, camera)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output!!.framebuffer)
            GLES20.glViewport(0, 0, width, height)
            drawQuad(copyProgram, baseCamera)
            GLES20.glDepthMask(true)
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
            if (vertices.capacity() < mesh.vertices.size) vertices = floats(mesh.vertices.size)
            if (indices.capacity() < mesh.indices.size) indices = ByteBuffer
                .allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            vertices.clear(); vertices.put(mesh.vertices).position(0)
            indices.clear(); indices.put(mesh.indices).position(0)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glColorMask(false, false, false, false)
            GLES20.glUseProgram(depthProgram)
            drawMesh(depthProgram, mesh.indices.size, false)
            GLES20.glColorMask(true, true, true, true)
            GLES20.glDepthMask(false)
            GLES20.glUseProgram(materialProgram)
            texture(materialProgram, "uCamera", 0, camera)
            texture(materialProgram, "uBase", 3, baseCamera)
            texture(materialProgram, "uSoft", 1, soft!!.texture)
            texture(materialProgram, "uRetouchMap", 2, smoothing?.texture ?: soft!!.texture)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(materialProgram, "uRetouch"), style.retouch)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(materialProgram, "uViewport"), width.toFloat(), height.toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(materialProgram, "uFeatureScale"),
                mesh.featureScale[0], mesh.featureScale[1])
            GLES20.glUniform3f(GLES20.glGetUniformLocation(materialProgram, "uColor"),
                style.color.red, style.color.green, style.color.blue)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(materialProgram, "uCoverage"), style.coverage)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(materialProgram, "uEyes[0]"), 16, mesh.eyes, 0)
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(materialProgram, "uReferences[0]"), 4, mesh.references, 0)
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(materialProgram, "uCharts[0]"), 4, chart.charts, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(materialProgram, "uUnderEyes[0]"), ConcealerFaceMesh.CONTOUR_POINTS, chart.contours, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(materialProgram, "uBaseHasSmoothing"), if (baseStyle != null) 1f else 0f)
            drawMesh(materialProgram, mesh.indices.size, true)
        } finally {
            GLES20.glColorMask(true, true, true, true)
            GLES20.glDepthMask(true)
            GLES20.glDepthFunc(GLES20.GL_LESS)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        }
        renderedRevision = smoothing?.revision ?: -1L
        renderedMesh = mesh; renderedStyle = style; renderedCamera = camera; renderedBase = baseCamera
        renderedBaseStyle = baseStyle
        return output!!.texture
    }

    private fun drawMesh(program: Int, count: Int, skin: Boolean) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, FoundationFaceMesh.COMPONENTS * 4, vertices)
        val attribute = if (skin) GLES20.glGetAttribLocation(program, "aSkin") else -1
        if (attribute >= 0) {
            vertices.position(4)
            GLES20.glEnableVertexAttribArray(attribute)
            GLES20.glVertexAttribPointer(attribute, 4, GLES20.GL_FLOAT, false, FoundationFaceMesh.COMPONENTS * 4, vertices)
        }
        indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, indices)
        GLES20.glDisableVertexAttribArray(position)
        if (attribute >= 0) GLES20.glDisableVertexAttribArray(attribute)
    }

    private fun drawQuad(program: Int, camera: Int) {
        GLES20.glUseProgram(program)
        texture(program, "uCamera", 0, camera)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun texture(program: Int, name: String, unit: Int, id: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
    }

    private fun createTarget(width: Int, height: Int, withDepth: Boolean): Target {
        val texture = IntArray(1); val framebuffer = IntArray(1); val depth = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture[0], 0)
        if (withDepth) {
            GLES20.glGenRenderbuffers(1, depth, 0)
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth[0])
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height)
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, depth[0])
        }
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
            GLES20.glDeleteTextures(1, texture, 0)
            if (depth[0] != 0) GLES20.glDeleteRenderbuffers(1, depth, 0)
            error("Concealer framebuffer incomplete: 0x${status.toString(16)}")
        }
        return Target(texture[0], framebuffer[0], depth[0])
    }

    private fun floats(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
}
