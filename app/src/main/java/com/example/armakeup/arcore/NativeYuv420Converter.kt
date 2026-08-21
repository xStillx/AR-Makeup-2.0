package com.example.armakeup.arcore

import android.media.Image
import java.nio.ByteBuffer

/** Small JNI boundary for the debug ARCore CPU image; conversion runs off the render thread. */
internal object NativeYuv420Converter {

    private val libraryLoadFailure by lazy {
        runCatching { System.loadLibrary(NATIVE_LIBRARY) }.exceptionOrNull()
    }

    fun convert(image: Image, target: ByteBuffer): Boolean {
        if (libraryLoadFailure != null || image.planes.size < 3) return false
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        return nativeConvert(
            yBuffer = y.buffer,
            yRowStride = y.rowStride,
            yPixelStride = y.pixelStride,
            uBuffer = u.buffer,
            uRowStride = u.rowStride,
            uPixelStride = u.pixelStride,
            vBuffer = v.buffer,
            vRowStride = v.rowStride,
            vPixelStride = v.pixelStride,
            width = image.width,
            height = image.height,
            rgbaBuffer = target,
        )
    }

    private external fun nativeConvert(
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        rgbaBuffer: ByteBuffer,
    ): Boolean

    private const val NATIVE_LIBRARY = "armakeup_vulkan"
}
