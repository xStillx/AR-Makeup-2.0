package com.example.armakeup.render

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder

/** Builds the first compositor materials on-device until host-side matc is wired into the build. */
internal object FilamentMaterialFactory {

    data class Materials(
        val camera: Material,
        val lipstick: Material,
    )

    fun build(engine: Engine, backend: MakeupRenderBackend): Materials {
        MaterialBuilder.init()
        return try {
            Materials(
                camera = buildCameraMaterial(engine, backend),
                lipstick = buildLipstickMaterial(engine, backend),
            )
        } finally {
            MaterialBuilder.shutdown()
        }
    }

    private fun buildCameraMaterial(
        engine: Engine,
        backend: MakeupRenderBackend,
    ): Material = buildMaterial(
        engine = engine,
        builder = commonExternalTextureBuilder("AR camera", backend)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .material(CAMERA_FRAGMENT),
    )

    private fun buildLipstickMaterial(
        engine: Engine,
        backend: MakeupRenderBackend,
    ): Material = buildMaterial(
        engine = engine,
        builder = commonExternalTextureBuilder("AR lipstick", backend)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "pigmentColor")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "matteCompression")
            .material(LIPSTICK_FRAGMENT),
    )

    private fun commonExternalTextureBuilder(
        name: String,
        backend: MakeupRenderBackend,
    ): MaterialBuilder = MaterialBuilder()
        .platform(MaterialBuilder.Platform.MOBILE)
        .targetApi(backend.targetApi)
        .name(name)
        .shading(MaterialBuilder.Shading.UNLIT)
        .samplerParameter(
            MaterialBuilder.SamplerType.SAMPLER_EXTERNAL,
            MaterialBuilder.SamplerFormat.FLOAT,
            MaterialBuilder.ParameterPrecision.MEDIUM,
            "cameraTexture",
        )
        .uniformParameter(MaterialBuilder.UniformType.MAT4, "cameraTextureTransform")
        .culling(MaterialBuilder.CullingMode.NONE)
        .depthWrite(true)
        .depthCulling(true)
        .optimization(MaterialBuilder.Optimization.PERFORMANCE)

    private val MakeupRenderBackend.targetApi: MaterialBuilder.TargetApi
        get() = when (this) {
            MakeupRenderBackend.VULKAN -> MaterialBuilder.TargetApi.VULKAN
            MakeupRenderBackend.OPENGL -> MaterialBuilder.TargetApi.OPENGL
        }

    private fun buildMaterial(engine: Engine, builder: MaterialBuilder): Material {
        val materialPackage = builder.build(engine)
        check(materialPackage.isValid) { "Filament material compilation failed" }
        val buffer = materialPackage.buffer
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }

    /**
     * The camera preview has already been display-referred and tone-mapped by the camera stack.
     * Filament's inverse helper reconstructs a linear working value before its final tone mapper.
     */
    private const val CAMERA_FRAGMENT = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            vec2 uv = (materialParams.cameraTextureTransform * vec4(getUV0(), 0.0, 1.0)).xy;
            vec3 cameraSrgb = texture(materialParams_cameraTexture, uv).rgb;
            material.baseColor = vec4(inverseTonemapSRGB(cameraSrgb), 1.0);
        }
    """

    /**
     * Coverage is supplied per vertex by the feathered lip mesh. Pigment chromaticity is mixed
     * over the native camera luminance, keeping folds and face lighting instead of flattening them.
     */
    private const val LIPSTICK_FRAGMENT = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            vec2 uv = (materialParams.cameraTextureTransform * vec4(getUV0(), 0.0, 1.0)).xy;
            vec3 cameraLinear = inverseTonemapSRGB(
                texture(materialParams_cameraTexture, uv).rgb
            );
            float coverage = clamp(getColor().a, 0.0, 1.0);
            const vec3 luminanceWeights = vec3(0.2126, 0.7152, 0.0722);
            float cameraLuminance = max(dot(cameraLinear, luminanceWeights), 0.0001);
            float pigmentLuminance = max(dot(materialParams.pigmentColor, luminanceWeights), 0.0001);
            vec3 luminancePreservingPigment = materialParams.pigmentColor *
                (cameraLuminance / pigmentLuminance);
            vec3 pigmented = mix(cameraLinear, luminancePreservingPigment, coverage);
            float highlight = smoothstep(0.55, 0.95, cameraLuminance);
            pigmented *= 1.0 - materialParams.matteCompression * highlight * coverage;
            material.baseColor = vec4(max(pigmented, vec3(0.0)), 1.0);
        }
    """
}
