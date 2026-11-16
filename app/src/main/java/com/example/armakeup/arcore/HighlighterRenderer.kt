package com.example.armakeup.arcore

import android.opengl.GLES20
import com.example.armakeup.makeup.HighlighterStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Direct depth-tested coat over the composed skin. No extra full-screen FBO or readback. */
internal class HighlighterRenderer(private val createProgram: (String, String) -> Int) {
    private var program = 0
    private var depthProgram = 0
    private val locations = HashMap<String, Int>()
    private var vertices: FloatBuffer = ByteBuffer.allocateDirect(0).asFloatBuffer()
    private var indices: ShortBuffer = ByteBuffer.allocateDirect(0).asShortBuffer()
    private var uploaded: HighlighterFaceMesh? = null

    fun onContextCreated() { program=0; depthProgram=0; locations.clear(); uploaded=null }

    fun draw(mesh: HighlighterFaceMesh, camera: Int, width: Int, height: Int, style: HighlighterStyle) {
        if (!style.enabled || style.intensity <= 0f || !(style.cheeks || style.nose || style.brows || style.cupid)) return
        if (program == 0) {
            program=createProgram(HighlighterMaterial.VERTEX,HighlighterMaterial.fragment())
            depthProgram=createProgram(BlushMaterial.DEPTH_VERTEX_SHADER,BlushMaterial.DEPTH_FRAGMENT_SHADER)
        }
        if (uploaded !== mesh) {
            if (vertices.capacity() < mesh.vertices.size) vertices=ByteBuffer.allocateDirect(mesh.vertices.size*4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            if (indices.capacity() < mesh.indices.size) indices=ByteBuffer.allocateDirect(mesh.indices.size*2)
                .order(ByteOrder.nativeOrder()).asShortBuffer()
            vertices.clear(); vertices.put(mesh.vertices).position(0)
            indices.clear(); indices.put(mesh.indices).position(0)
            uploaded=mesh
        }
        try {
            GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glDepthMask(true); GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glColorMask(false,false,false,false)
            GLES20.glUseProgram(depthProgram); drawMesh(depthProgram,mesh.indices.size,false)
            GLES20.glColorMask(true,true,true,true); GLES20.glDepthMask(false)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,camera)
            GLES20.glUniform1i(location("uCamera"),0)
            GLES20.glUniform2f(location("uViewport"),width.toFloat(),height.toFloat())
            GLES20.glUniform2f(location("uFeatureScale"),mesh.face.featureScale[0],mesh.face.featureScale[1])
            GLES20.glUniform1f(location("uCoverage"),1f)
            GLES20.glUniform1f(location("uLeftHalfOnly"),0f)
            GLES20.glUniform1f(location("uIntensity"),style.intensity)
            GLES20.glUniform1f(location("uPixelMetres"),2f*mesh.face.featureScale[1]/height)
            val finish=style.shade.finish
            GLES20.glUniform4f(location("uFinish"),finish.roughness,finish.sheen,finish.specular,finish.tint)
            GLES20.glUniform4f(location("uFlakes"),finish.flakes,finish.flakeSize,finish.flakeDensity,finish.duo)
            GLES20.glUniform4f(location("uZones"),if(style.cheeks)1f else 0f,if(style.nose)1f else 0f,
                if(style.brows)1f else 0f,if(style.cupid)1f else 0f)
            val color=style.shade.color; val shift=style.shade.shift
            GLES20.glUniform3f(location("uHighlightColor"),color.red,color.green,color.blue)
            GLES20.glUniform3f(location("uShiftColor"),shift.red,shift.green,shift.blue)
            GLES20.glUniform4fv(location("uBoundary[0]"),36,mesh.face.boundary,0)
            GLES20.glUniform2fv(location("uLips[0]"),20,mesh.face.lips,0)
            GLES20.glUniform4fv(location("uEyes[0]"),16,mesh.face.eyes,0)
            GLES20.glUniform4fv(location("uBrows[0]"),10,mesh.face.brows,0)
            GLES20.glUniform4fv(location("uNostrils"),1,mesh.face.nostrils,0)
            GLES20.glUniform3fv(location("uReferences[0]"),4,mesh.face.references,0)
            GLES20.glUniform3fv(location("uReferenceDirections[0]"),4,mesh.referenceDirections,0)
            GLES20.glUniform4fv(location("uCheeks[0]"),16,mesh.cheeks,0)
            GLES20.glUniform4fv(location("uNose[0]"),3,mesh.nose,0)
            GLES20.glUniform4fv(location("uBrowCoat[0]"),6,mesh.brows,0)
            GLES20.glUniform4fv(location("uCupid[0]"),3,mesh.cupid,0)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_COLOR,GLES20.GL_ZERO,GLES20.GL_ONE)
            drawMesh(program,mesh.indices.size,true)
        } finally {
            GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glColorMask(true,true,true,true); GLES20.glDepthMask(true)
            GLES20.glDepthFunc(GLES20.GL_LESS); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
    }

    private fun location(name: String) = locations.getOrPut(name) { GLES20.glGetUniformLocation(program,name) }

    private fun drawMesh(active: Int, count: Int, coat: Boolean) {
        val attributes=if(coat) arrayOf("aPosition" to (0 to 4),"aLocal" to (4 to 3),
            "aNormal" to (7 to 3),"aView" to (10 to 3)) else arrayOf("aPosition" to (0 to 4))
        for ((name,format) in attributes) {
            val key="attribute:$active:$name"
            val id=locations.getOrPut(key) { GLES20.glGetAttribLocation(active,name) }
            vertices.position(format.first)
            GLES20.glEnableVertexAttribArray(id)
            GLES20.glVertexAttribPointer(id,format.second,GLES20.GL_FLOAT,false,HighlighterFaceMesh.COMPONENTS*4,vertices)
        }
        indices.position(0); GLES20.glDrawElements(GLES20.GL_TRIANGLES,count,GLES20.GL_UNSIGNED_SHORT,indices)
        for ((name,_) in attributes) GLES20.glDisableVertexAttribArray(locations.getValue("attribute:$active:$name"))
    }
}
