package com.example.armakeup.arcore

import android.opengl.GLES20

/** Two reusable full-resolution render targets for the separable lip alpha blur. */
internal class LipBlurFrameStore {
    private var targets = emptyList<Target>()
    private var width = 0
    private var height = 0

    val sourceTextureId: Int get() = targets[0].textureId
    val intermediateTextureId: Int get() = targets[1].textureId

    fun ensureSize(width: Int, height: Int) {
        if (this.width == width && this.height == height && targets.isNotEmpty()) return
        release()
        targets = listOf(createTarget(width, height), createTarget(width, height))
        this.width = width
        this.height = height
    }

    fun bindSource() = bind(0)

    fun bindIntermediate() = bind(1)

    /** Old names belong to the lost context; never delete them in the new context. */
    fun onContextCreated() {
        targets = emptyList()
        width = 0
        height = 0
    }

    fun release() {
        targets.forEach {
            GLES20.glDeleteFramebuffers(1, intArrayOf(it.framebufferId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0)
        }
        onContextCreated()
    }

    private fun bind(index: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[index].framebufferId)
    }

    private fun createTarget(width: Int, height: Int): Target {
        val texture = IntArray(1)
        val framebuffer = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture[0], 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
            GLES20.glDeleteTextures(1, texture, 0)
            error("Lip blur framebuffer incomplete: 0x${status.toString(16)}")
        }
        return Target(texture[0], framebuffer[0])
    }

    private data class Target(val textureId: Int, val framebufferId: Int)
}
