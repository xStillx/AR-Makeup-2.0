package com.example.armakeup.render

import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.example.armakeup.R
import com.example.armakeup.makeup.LipLandmarkTopology
import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile
import com.example.armakeup.tracking.FillCenterTransform
import com.example.armakeup.tracking.LandmarkRenderFrame
import com.example.armakeup.tracking.NormalizedImageTransform
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.Stream
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.ChoreographerHelper
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.FilamentHelper
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread-owned Filament compositor. */
internal class FilamentMakeupRenderer(
    context: Context,
    private val surfaceView: SurfaceView,
    private val onError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val displayHelper = DisplayHelper(context)
    private val engineSelection = FilamentEngineFactory.create(context)
    private val engine = engineSelection.engine
    private val activeBackend = engineSelection.activeBackend
    private val nativeVulkanProbe = NativeVulkanBootstrap.probe()
    private val nativeVulkanRuntime = if (
        engineSelection.requestedBackend == MakeupRenderBackend.VULKAN
    ) {
        NativeVulkanDiagnosticRuntime.createOrNull(nativeVulkanProbe)
    } else {
        null
    }
    private val filamentRenderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val frameScheduler = FrameScheduler()
    private val materials = engineSelection.materials
    private val cameraMaterialInstance = materials.camera.createInstance()
    private val upperLipMaterialInstance = materials.lipstick.createInstance()
    private val lowerLipMaterialInstance = materials.lipstick.createInstance()
    private val cameraTexture = Texture.Builder()
        .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
        .format(Texture.InternalFormat.RGB8)
        .build(engine)
    private val textureSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )
    private val skybox = Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine)
    private val lipTessellator = LipMeshTessellator()
    private val cameraMesh = createCameraMesh()
    private val lipMesh = createLipMesh()
    private val lipVertexUploader = DynamicVertexUploader(
        engine = engine,
        vertexBuffer = lipMesh.vertexBuffer,
        byteCount = lipTessellator.vertexCount * LIP_VERTEX_STRIDE_BYTES,
        handler = mainHandler,
    )
    private val outerPoints = FloatArray(LipLandmarkTopology.outerContour.size * POINT_SIZE)
    private val innerPoints = FloatArray(LipLandmarkTopology.innerContour.size * POINT_SIZE)

    private var swapChain: SwapChain? = null
    private var cameraInput: CameraInput? = null
    private var activeSurfaceRequest: SurfaceRequest? = null
    private var latestLandmarks: LandmarkState? = null
    private var latestCameraTransform: VulkanCameraTransform? = null
    private var lipEntityVisible = false
    private var resumed = false
    private var destroyRequested = false
    private var destroyed = false
    private var fatalErrorDelivered = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    @get:StringRes
    internal val renderBackendLabelRes: Int
        get() = when {
            activeBackend == MakeupRenderBackend.VULKAN -> R.string.render_backend_vulkan
            cameraInput is VulkanCameraInput ->
                R.string.render_backend_opengl_fallback_vulkan_camera
            nativeVulkanRuntime?.isReady == true ->
                R.string.render_backend_opengl_fallback_vulkan_runtime
            engineSelection.requestedBackend == MakeupRenderBackend.VULKAN ->
                R.string.render_backend_opengl_fallback
            else -> R.string.render_backend_opengl
        }

    init {
        Log.i(RENDER_LOG_TAG, engineSelection.diagnostic)
        Log.i(NATIVE_VULKAN_LOG_TAG, nativeVulkanProbe.diagnostic)
        configureMaterialInstances(context)
        configureScene()
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
        frameScheduler.setRenderer(filamentRenderer)
    }

    fun onSurfaceRequested(request: SurfaceRequest) {
        ensureMainThread()
        if (destroyRequested || destroyed) {
            request.willNotProvideSurface()
            return
        }
        if (activeSurfaceRequest != null) {
            request.willNotProvideSurface()
            reportFatalError("CameraX requested overlapping preview surfaces")
            return
        }

        try {
            val input = obtainCameraInput(request.resolution.width, request.resolution.height)
            activeSurfaceRequest = request
            request.setTransformationInfoListener(mainExecutor) { info ->
                if (activeSurfaceRequest === request) {
                    applyCameraTransform(request, info)
                }
            }
            request.provideSurface(input.surface, mainExecutor) {
                if (activeSurfaceRequest === request) {
                    activeSurfaceRequest = null
                }
                if (destroyRequested) finishDestroy()
            }
        } catch (error: RuntimeException) {
            activeSurfaceRequest = null
            request.willNotProvideSurface()
            reportFatalError(error.message ?: "Failed to create camera GPU stream")
        }
    }

    fun setResult(
        landmarks: LandmarkRenderFrame,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontal: Boolean,
        sensorTimestampNs: Long,
    ) {
        ensureMainThread()
        latestLandmarks = LandmarkState(
            landmarks,
            sourceWidth,
            sourceHeight,
            rotationDegrees,
            mirrorHorizontal,
            sensorTimestampNs,
        )
    }

    fun clear() {
        ensureMainThread()
        latestLandmarks = null
        hideLipEntity()
    }

    fun resume() {
        ensureMainThread()
        if (destroyRequested || destroyed || resumed) return
        resumed = true
        nativeVulkanRuntime?.start()
        frameScheduler.post()
    }

    fun pause() {
        ensureMainThread()
        if (!resumed) return
        resumed = false
        frameScheduler.remove()
        nativeVulkanRuntime?.stop()
    }

    fun destroy() {
        ensureMainThread()
        if (destroyRequested || destroyed) return
        destroyRequested = true
        pause()
        uiHelper.detach()
        val request = activeSurfaceRequest
        if (request == null) {
            finishDestroy()
        } else {
            request.invalidate()
        }
    }

    private fun configureMaterialInstances(context: Context) {
        listOf(
            cameraMaterialInstance,
            upperLipMaterialInstance,
            lowerLipMaterialInstance,
        ).forEach { instance ->
            instance.setParameter("cameraTexture", cameraTexture, textureSampler)
            setCameraTextureTransform(instance, IDENTITY_MATRIX)
        }
        setPigmentColor(
            upperLipMaterialInstance,
            ContextCompat.getColor(context, R.color.lipstick_matte_upper),
        )
        setPigmentColor(
            lowerLipMaterialInstance,
            ContextCompat.getColor(context, R.color.lipstick_matte_lower),
        )
        upperLipMaterialInstance.setParameter(
            "matteCompression",
            ReferenceMatteLipstickProfile.upper.matteCoverage / MAX_ALPHA,
        )
        lowerLipMaterialInstance.setParameter(
            "matteCompression",
            ReferenceMatteLipstickProfile.lower.matteCoverage / MAX_ALPHA,
        )
    }

    private fun setPigmentColor(material: MaterialInstance, color: Int) {
        material.setParameter(
            "pigmentColor",
            Colors.RgbType.SRGB,
            Color.red(color) / MAX_COLOR_CHANNEL,
            Color.green(color) / MAX_COLOR_CHANNEL,
            Color.blue(color) / MAX_COLOR_CHANNEL,
        )
    }

    private fun configureScene() {
        camera.setProjection(Camera.Projection.ORTHO, -1.0, 1.0, -1.0, 1.0, 0.1, 10.0)
        camera.lookAt(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        scene.skybox = skybox
        scene.addEntity(cameraMesh.entity)
        view.camera = camera
        view.scene = scene
        view.isPostProcessingEnabled = true
        view.isFrustumCullingEnabled = false
    }

    private fun obtainCameraInput(width: Int, height: Int): CameraInput {
        check(activeBackend == MakeupRenderBackend.OPENGL) {
            "Filament camera input is restricted to the verified OpenGL bridge"
        }
        val current = cameraInput
        if (current != null && current.width == width && current.height == height) {
            return current
        }
        current?.close()
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createVulkanCameraInputOrNull(width, height) ?: AcquiredCameraInput(width, height)
        } else {
            NativeCameraInput(width, height)
        }
        Log.i(
            RENDER_LOG_TAG,
            "cameraInput=${created.javaClass.simpleName} size=${width}x$height",
        )
        cameraInput = created
        return created
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createVulkanCameraInputOrNull(width: Int, height: Int): CameraInput? {
        val runtime = nativeVulkanRuntime?.takeIf { it.isReady } ?: return null
        return runCatching {
            VulkanCameraInput(width, height, runtime)
        }.onFailure { error ->
            runtime.closeCamera()
            Log.e(NATIVE_VULKAN_LOG_TAG, "Native camera bridge unavailable; using GL reader", error)
        }.getOrNull()
    }

    private fun applyCameraTransform(
        request: SurfaceRequest,
        info: SurfaceRequest.TransformationInfo,
    ) {
        val cropRect = info.cropRect
        val matrix = CameraTextureTransform.matrix(
            bufferWidth = request.resolution.width,
            bufferHeight = request.resolution.height,
            crop = CameraTextureTransform.CropRegion(
                cropRect.left,
                cropRect.top,
                cropRect.right,
                cropRect.bottom,
            ),
            rotationDegrees = info.rotationDegrees,
            mirrorHorizontal = info.isMirroring,
            invertDisplayHorizontally =
                cameraInput?.requiresHorizontalUvCompensation == true,
            invertDisplayVertically = cameraInput?.requiresVerticalUvCompensation == true,
        )
        latestCameraTransform = VulkanCameraTransform(
            cropLeft = cropRect.left,
            cropTop = cropRect.top,
            cropRight = cropRect.right,
            cropBottom = cropRect.bottom,
            rotationDegrees = ((info.rotationDegrees % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION,
            mirrorHorizontal = info.isMirroring,
            matrix = matrix,
        )
        listOf(
            cameraMaterialInstance,
            upperLipMaterialInstance,
            lowerLipMaterialInstance,
        ).forEach { setCameraTextureTransform(it, matrix) }
    }

    private fun setCameraTextureTransform(material: MaterialInstance, matrix: FloatArray) {
        material.setParameter(
            "cameraTextureTransform",
            MaterialInstance.FloatElement.MAT4,
            matrix,
            0,
            1,
        )
    }

    private fun updateLipGeometry(renderTimestampMs: Long) {
        val state = latestLandmarks ?: run {
            hideLipEntity()
            return
        }
        if (
            viewportWidth <= 0 ||
            viewportHeight <= 0 ||
            state.sourceWidth <= 0 ||
            state.sourceHeight <= 0
        ) {
            return
        }
        if (state.landmarks.size <= MAX_REQUIRED_LANDMARK_INDEX) {
            hideLipEntity()
            return
        }

        val predictionSeconds = state.landmarks.predictionSeconds(renderTimestampMs)
        val fillTransform = FillCenterTransform.calculate(
            viewWidth = viewportWidth,
            viewHeight = viewportHeight,
            sourceWidth = state.sourceWidth,
            sourceHeight = state.sourceHeight,
        )
        val imageTransform = NormalizedImageTransform(
            state.rotationDegrees,
            state.mirrorHorizontal,
        )
        writeContour(
            state,
            predictionSeconds,
            fillTransform,
            imageTransform,
            LipLandmarkTopology.outerContour,
            outerPoints,
        )
        writeContour(
            state,
            predictionSeconds,
            fillTransform,
            imageTransform,
            LipLandmarkTopology.innerContour,
            innerPoints,
        )
        val tessellated = lipTessellator.tessellate(
            outerPoints,
            innerPoints,
            ReferenceMatteLipstickProfile.upper,
            ReferenceMatteLipstickProfile.lower,
        )
        lipVertexUploader.upload { buffer -> writeLipVertices(buffer, tessellated) }
        showLipEntity()
    }

    private fun writeContour(
        state: LandmarkState,
        predictionSeconds: Float,
        fillTransform: FillCenterTransform,
        imageTransform: NormalizedImageTransform,
        topology: IntArray,
        output: FloatArray,
    ) {
        topology.forEachIndexed { pointIndex, landmarkIndex ->
            val rawX = state.landmarks.x(landmarkIndex, predictionSeconds)
            val rawY = state.landmarks.y(landmarkIndex, predictionSeconds)
            val displayX = imageTransform.mapX(rawX, rawY)
            val displayY = imageTransform.mapY(rawX, rawY)
            val outputIndex = pointIndex * POINT_SIZE
            output[outputIndex] = fillTransform.mapX(displayX, state.sourceWidth) / viewportWidth
            output[outputIndex + 1] = fillTransform.mapY(displayY, state.sourceHeight) / viewportHeight
        }
    }

    private fun writeLipVertices(buffer: ByteBuffer, tessellated: FloatArray) {
        var sourceIndex = 0
        while (sourceIndex < tessellated.size) {
            val displayX = tessellated[sourceIndex]
            val displayY = tessellated[sourceIndex + 1]
            val coverage = tessellated[sourceIndex + 2]
            buffer.putFloat(displayX * 2f - 1f)
            buffer.putFloat(1f - displayY * 2f)
            buffer.putFloat(LIP_Z)
            buffer.putFloat(displayX)
            buffer.putFloat(1f - displayY)
            buffer.putFloat(1f)
            buffer.putFloat(1f)
            buffer.putFloat(1f)
            buffer.putFloat(coverage)
            sourceIndex += LipMeshTessellator.VERTEX_COMPONENT_COUNT
        }
    }

    private fun showLipEntity() {
        if (lipEntityVisible) return
        scene.addEntity(lipMesh.entity)
        lipEntityVisible = true
    }

    private fun hideLipEntity() {
        if (!lipEntityVisible) return
        scene.removeEntity(lipMesh.entity)
        lipEntityVisible = false
    }

    private fun createCameraMesh(): MeshResources {
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(CAMERA_VERTEX_COUNT)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                CAMERA_VERTEX_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                POSITION_COMPONENT_COUNT * FLOAT_BYTES,
                CAMERA_VERTEX_STRIDE_BYTES,
            )
            .build(engine)
        val vertexData = nativeBuffer(CAMERA_VERTEX_COUNT * CAMERA_VERTEX_STRIDE_BYTES).apply {
            putCameraVertex(-1f, 1f, 0f, 1f)
            putCameraVertex(-1f, -1f, 0f, 0f)
            putCameraVertex(1f, -1f, 1f, 0f)
            putCameraVertex(1f, 1f, 1f, 1f)
            flip()
        }
        vertexBuffer.setBufferAt(engine, 0, vertexData)

        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
        val indexBuffer = createIndexBuffer(indices)
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, CAMERA_Z, 1f, 1f, 0.1f))
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                0,
                indices.size,
            )
            .material(0, cameraMaterialInstance)
            .build(engine, entity)
        return MeshResources(entity, vertexBuffer, indexBuffer)
    }

    private fun ByteBuffer.putCameraVertex(x: Float, y: Float, u: Float, v: Float) {
        putFloat(x)
        putFloat(y)
        putFloat(CAMERA_Z)
        putFloat(u)
        putFloat(v)
    }

    private fun createLipMesh(): MeshResources {
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(lipTessellator.vertexCount)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                LIP_VERTEX_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                POSITION_COMPONENT_COUNT * FLOAT_BYTES,
                LIP_VERTEX_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.COLOR,
                0,
                VertexBuffer.AttributeType.FLOAT4,
                (POSITION_COMPONENT_COUNT + UV_COMPONENT_COUNT) * FLOAT_BYTES,
                LIP_VERTEX_STRIDE_BYTES,
            )
            .build(engine)
        vertexBuffer.setBufferAt(
            engine,
            0,
            nativeBuffer(lipTessellator.vertexCount * LIP_VERTEX_STRIDE_BYTES),
        )
        val indexBuffer = createIndexBuffer(lipTessellator.indices)
        val entity = EntityManager.get().create()
        RenderableManager.Builder(2)
            .boundingBox(Box(0f, 0f, LIP_Z, 1.25f, 1.25f, 0.1f))
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                0,
                lipTessellator.indicesPerLip,
            )
            .geometry(
                1,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                lipTessellator.indicesPerLip,
                lipTessellator.indicesPerLip,
            )
            .material(0, upperLipMaterialInstance)
            .material(1, lowerLipMaterialInstance)
            .build(engine, entity)
        return MeshResources(entity, vertexBuffer, indexBuffer)
    }

    private fun createIndexBuffer(indices: ShortArray): IndexBuffer {
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        val data = nativeBuffer(indices.size * SHORT_BYTES)
        indices.forEach(data::putShort)
        data.flip()
        indexBuffer.setBuffer(engine, data)
        return indexBuffer
    }

    private fun nativeBuffer(byteCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())

    private fun reportFatalError(message: String) {
        if (fatalErrorDelivered) return
        fatalErrorDelivered = true
        pause()
        onError(message)
    }

    private fun finishDestroy() {
        if (destroyed) return
        destroyed = true
        hideLipEntity()
        cameraInput?.close()
        cameraInput = null
        nativeVulkanRuntime?.close()
        destroyMesh(lipMesh)
        destroyMesh(cameraMesh)
        engine.destroyMaterialInstance(lowerLipMaterialInstance)
        engine.destroyMaterialInstance(upperLipMaterialInstance)
        engine.destroyMaterialInstance(cameraMaterialInstance)
        engine.destroyMaterial(materials.lipstick)
        engine.destroyMaterial(materials.camera)
        engine.destroyTexture(cameraTexture)
        engine.destroySkybox(skybox)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroyRenderer(filamentRenderer)
        swapChain?.let(engine::destroySwapChain)
        swapChain = null
        EntityManager.get().destroy(cameraEntity)
        engine.flushAndWait()
        engine.destroy()
    }

    private fun destroyMesh(mesh: MeshResources) {
        engine.destroyEntity(mesh.entity)
        engine.destroyVertexBuffer(mesh.vertexBuffer)
        engine.destroyIndexBuffer(mesh.indexBuffer)
        EntityManager.get().destroy(mesh.entity)
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Filament compositor must be accessed from the main thread"
        }
    }

    private inner class FrameScheduler : ChoreographerHelper() {
        override fun onFrame(frameTimeNanos: Long) {
            if (!resumed || destroyRequested || destroyed || !uiHelper.isReadyToRender) return
            try {
                cameraInput?.pushLatestFrame()
                updateLipGeometry(SystemClock.uptimeMillis())
                val currentSwapChain = swapChain ?: return
                if (filamentRenderer.beginFrame(currentSwapChain, frameTimeNanos)) {
                    filamentRenderer.render(view)
                    filamentRenderer.endFrame()
                }
            } catch (error: RuntimeException) {
                reportFatalError(error.message ?: "Filament render failure")
            }
        }
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            if (destroyed) return
            swapChain?.let(engine::destroySwapChain)
            swapChain = engine.createSwapChain(surface)
            displayHelper.attach(filamentRenderer, surfaceView.display)
        }

        override fun onDetachedFromSurface() {
            if (destroyed) return
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            if (destroyed) return
            viewportWidth = width
            viewportHeight = height
            view.viewport = Viewport(0, 0, width, height)
            FilamentHelper.synchronizePendingFrames(engine)
        }
    }

    private data class LandmarkState(
        val landmarks: LandmarkRenderFrame,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rotationDegrees: Int,
        val mirrorHorizontal: Boolean,
        val sensorTimestampNs: Long,
    )

    private data class MeshResources(
        val entity: Int,
        val vertexBuffer: VertexBuffer,
        val indexBuffer: IndexBuffer,
    )

    private interface CameraInput {
        val width: Int
        val height: Int
        val surface: Surface
        val requiresHorizontalUvCompensation: Boolean
        val requiresVerticalUvCompensation: Boolean
        fun pushLatestFrame()
        fun close()
    }

    /** API 24–28 compatibility path. Filament NATIVE streams are copy-free but unsynchronized. */
    private inner class NativeCameraInput(
        override val width: Int,
        override val height: Int,
    ) : CameraInput {
        private val surfaceTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(width, height)
        }
        private val stream = Stream.Builder()
            .stream(surfaceTexture)
            .width(width)
            .height(height)
            .build(engine)
        override val surface: Surface = Surface(surfaceTexture)
        override val requiresHorizontalUvCompensation: Boolean = false
        override val requiresVerticalUvCompensation: Boolean = false

        init {
            cameraTexture.setExternalStream(engine, stream)
        }

        override fun pushLatestFrame() = Unit

        override fun close() {
            surface.release()
            engine.destroyStream(stream)
            engine.flushAndWait()
            surfaceTexture.release()
        }
    }

    /** API 29+ OpenGL bridge with explicit HardwareBuffer acquisition and release callbacks. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class VulkanCameraInput(
        override val width: Int,
        override val height: Int,
        private val runtime: NativeVulkanDiagnosticRuntime,
    ) : CameraInput {
        private val stream = Stream.Builder()
            .width(width)
            .height(height)
            .build(engine)
        override val surface: Surface = checkNotNull(runtime.configureCamera(width, height)) {
            "Native Vulkan camera surface creation failed"
        }
        override val requiresHorizontalUvCompensation: Boolean = true
        override val requiresVerticalUvCompensation: Boolean = true
        private val firstFrameLogged = AtomicBoolean(false)

        init {
            cameraTexture.setExternalStream(engine, stream)
        }

        override fun pushLatestFrame() {
            val transform = latestCameraTransform
            if (transform == null) return
            val frame = runtime.acquireCameraFrame(
                transform = transform,
                landmarkSensorTimestampNs = latestLandmarks?.sensorTimestampNs,
            )
            if (frame == null) return
            try {
                stream.setAcquiredImage(frame.hardwareBuffer, mainHandler) {
                    runtime.releaseCameraFrame(frame)
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    val landmarkAgeMs = frame.landmarkAgeNs?.div(NANOS_PER_MILLISECOND)
                    Log.i(
                        NATIVE_VULKAN_LOG_TAG,
                        "cameraFrame token=${frame.nativeToken} " +
                            "timestampNs=${frame.sensorTimestampNs} size=${frame.width}x${frame.height} " +
                            "format=${frame.hardwareBufferFormat} usage=${frame.hardwareBufferUsage} " +
                            "acquireFence=${frame.acquireFenceImported} " +
                            "releaseFence=${frame.releaseFenceExported} landmarkAgeMs=$landmarkAgeMs",
                    )
                }
            } catch (error: RuntimeException) {
                runtime.releaseCameraFrame(frame)
                throw error
            }
        }

        override fun close() {
            engine.destroyStream(stream)
            engine.flushAndWait()
            surface.release()
            runtime.closeCamera()
        }
    }

    /** API 29+ fallback when the native AImageReader/importer is unavailable. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class AcquiredCameraInput(
        override val width: Int,
        override val height: Int,
    ) : CameraInput {
        private val imageReader = ImageReader.newInstance(
            width,
            height,
            ImageFormat.PRIVATE,
            ACQUIRED_MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        private val stream = Stream.Builder()
            .width(width)
            .height(height)
            .build(engine)
        override val surface: Surface = imageReader.surface
        override val requiresHorizontalUvCompensation: Boolean =
            activeBackend == MakeupRenderBackend.OPENGL
        override val requiresVerticalUvCompensation: Boolean =
            activeBackend == MakeupRenderBackend.OPENGL

        init {
            cameraTexture.setExternalStream(engine, stream)
        }

        override fun pushLatestFrame() {
            val image = try {
                imageReader.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null
            } ?: return
            val hardwareBuffer = image.hardwareBuffer
            if (hardwareBuffer == null) {
                image.close()
                return
            }
            stream.setAcquiredImage(hardwareBuffer, mainHandler) {
                image.close()
            }
        }

        override fun close() {
            engine.destroyStream(stream)
            engine.flushAndWait()
            imageReader.close()
        }
    }

    private class DynamicVertexUploader(
        private val engine: Engine,
        private val vertexBuffer: VertexBuffer,
        byteCount: Int,
        private val handler: Handler,
    ) {
        private val slots = Array(UPLOAD_BUFFER_COUNT) {
            UploadSlot(ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()))
        }

        fun upload(write: (ByteBuffer) -> Unit) {
            val slot = slots.firstOrNull { !it.inUse } ?: return
            slot.inUse = true
            slot.buffer.clear()
            write(slot.buffer)
            slot.buffer.flip()
            vertexBuffer.setBufferAt(
                engine,
                0,
                slot.buffer,
                0,
                slot.buffer.remaining(),
                handler,
            ) {
                slot.inUse = false
            }
        }

        private data class UploadSlot(
            val buffer: ByteBuffer,
            var inUse: Boolean = false,
        )

        companion object {
            private const val UPLOAD_BUFFER_COUNT = 3
        }
    }

    companion object {
        private const val FLOAT_BYTES = 4
        private const val SHORT_BYTES = 2
        private const val POSITION_COMPONENT_COUNT = 3
        private const val UV_COMPONENT_COUNT = 2
        private const val COLOR_COMPONENT_COUNT = 4
        private const val CAMERA_VERTEX_COUNT = 4
        private const val CAMERA_VERTEX_STRIDE_BYTES =
            (POSITION_COMPONENT_COUNT + UV_COMPONENT_COUNT) * FLOAT_BYTES
        private const val LIP_VERTEX_STRIDE_BYTES =
            (POSITION_COMPONENT_COUNT + UV_COMPONENT_COUNT + COLOR_COMPONENT_COUNT) * FLOAT_BYTES
        private const val POINT_SIZE = 2
        private const val CAMERA_Z = 0f
        private const val LIP_Z = 0.05f
        private const val MAX_ALPHA = 255f
        private const val MAX_COLOR_CHANNEL = 255f
        private const val ACQUIRED_MAX_IMAGES = 4
        private const val FULL_ROTATION = 360
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val RENDER_LOG_TAG = "ARMakeupRender"
        private const val NATIVE_VULKAN_LOG_TAG = "ARMakeupVulkan"
        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        private val MAX_REQUIRED_LANDMARK_INDEX = maxOf(
            LipLandmarkTopology.outerContour.max(),
            LipLandmarkTopology.innerContour.max(),
        )
    }
}
