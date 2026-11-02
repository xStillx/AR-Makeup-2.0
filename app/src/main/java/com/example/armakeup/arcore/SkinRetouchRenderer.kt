package com.example.armakeup.arcore

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Camera-derived smoothing target for the skin materials and its exact pair. */
internal class SkinRetouchRenderer(private val createProgram: (String,String)->Int) {
    data class Frame(val texture: Int,val revision: Long)
    private data class Target(val texture: Int,val framebuffer: Int)
    private var horizontal: Target? = null
    private var filtered: Target? = null
    private var program = 0
    private var width = 0
    private var height = 0
    private var revision = 0L
    private var cachedMesh: FoundationFaceMesh? = null
    private var cachedCamera = 0
    private var cachedFrame: Frame? = null
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f));position(0)
    }

    fun onContextCreated() {
        horizontal=null;filtered=null;program=0;width=0;height=0
        cachedMesh=null;cachedFrame=null;cachedCamera=0
    }

    fun releaseTargets() {
        for(target in listOfNotNull(horizontal,filtered)) {
            GLES20.glDeleteTextures(1,intArrayOf(target.texture),0)
            GLES20.glDeleteFramebuffers(1,intArrayOf(target.framebuffer),0)
        }
        horizontal=null;filtered=null;width=0;height=0;cachedMesh=null;cachedFrame=null;cachedCamera=0
    }

    fun render(mesh: FoundationFaceMesh,camera: Int,viewportWidth: Int,viewportHeight: Int): Frame {
        val scale=minOf(1f,512f/maxOf(viewportWidth,viewportHeight))
        val w=(viewportWidth*scale).roundToInt().coerceAtLeast(1)
        val h=(viewportHeight*scale).roundToInt().coerceAtLeast(1)
        if(width==w && height==h && cachedMesh===mesh && cachedCamera==camera) cachedFrame?.let{return it}
        if(program==0) program=createProgram(FoundationMaterial.QUAD_VERTEX,SkinRetouchMaterial.FRAGMENT)
        if(width!=w || height!=h || filtered==null) {
            releaseTargets();width=w;height=h
            horizontal=createTarget(w,h);filtered=createTarget(w,h)
        }
        // Accepted preview kernel: sigma3mm, step1mm, no chroma correction.
        val dx=(0.001f/(2f*mesh.featureScale[0])).coerceAtLeast(0.5f/viewportWidth)
        val dy=(0.001f/(2f*mesh.featureScale[1])).coerceAtLeast(0.5f/viewportHeight)
        try {
            GLES20.glDisable(GLES20.GL_BLEND);GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glViewport(0,0,w,h);GLES20.glUseProgram(program)
            bind("uGuide",1,camera)
            draw(horizontal!!,camera,dx,0f)
            draw(filtered!!,horizontal!!.texture,0f,dy)
        } finally {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
            GLES20.glViewport(0,0,viewportWidth,viewportHeight)
        }
        cachedMesh=mesh;cachedCamera=camera
        return Frame(filtered!!.texture,++revision).also{cachedFrame=it}
    }

    private fun draw(target: Target,camera: Int,x: Float,y: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target.framebuffer)
        bind("uCamera",0,camera)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"uRadius"),x,y)
        val position=GLES20.glGetAttribLocation(program,"aPosition")
        quad.position(0);GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun bind(name: String,unit: Int,texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0+unit);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,name),unit)
    }

    private fun createTarget(w: Int,h: Int): Target {
        val texture=IntArray(1);val framebuffer=IntArray(1)
        GLES20.glGenTextures(1,texture,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0])
        for((key,value) in listOf(GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR,
            GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE,
            GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,key,value)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
        GLES20.glGenFramebuffers(1,framebuffer,0);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture[0],0)
        val status=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if(status!=GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteTextures(1,texture,0);GLES20.glDeleteFramebuffers(1,framebuffer,0)
            error("Skin retouch framebuffer incomplete: 0x${status.toString(16)}")
        }
        return Target(texture[0],framebuffer[0])
    }
}
