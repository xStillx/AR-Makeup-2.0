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
            .require(MaterialBuilder.VertexAttribute.UV1)
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "pigmentColor")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT2, "illuminationSampleStep")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "roughness")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "specularStrength")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "highlightRetention")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "microTextureRetention")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "wetInnerEdgeStrength")
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
     * The mesh supplies coverage, an interpolated reconstructed normal and lip-local coordinates.
     * Broad illumination comes from the live camera gradient while native positive detail anchors
     * specular response to the captured light. No static highlight texture is used.
     */
    private const val LIPSTICK_FRAGMENT = """
        vec2 cameraUv(vec2 displayUv) {
            return (materialParams.cameraTextureTransform *
                vec4(clamp(displayUv, vec2(0.0), vec2(1.0)), 0.0, 1.0)).xy;
        }

        vec3 cameraLinearAt(vec2 displayUv) {
            return inverseTonemapSRGB(
                texture(materialParams_cameraTexture, cameraUv(displayUv)).rgb
            );
        }

        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            vec2 displayUv = getUV0();
            vec3 cameraLinear = cameraLinearAt(displayUv);
            float coverage = clamp(getColor().a, 0.0, 1.0);
            vec3 lipNormal = normalize(getColor().rgb * 2.0 - 1.0);
            vec2 lipUv = getUV1();
            const vec3 luminanceWeights = vec3(0.2126, 0.7152, 0.0722);
            float cameraLuminance = max(dot(cameraLinear, luminanceWeights), 0.0001);

            vec2 sampleStep = max(
                materialParams.illuminationSampleStep,
                vec2(0.0001)
            );
            float luminanceLeft = dot(
                cameraLinearAt(displayUv - vec2(sampleStep.x, 0.0)),
                luminanceWeights
            );
            float luminanceRight = dot(
                cameraLinearAt(displayUv + vec2(sampleStep.x, 0.0)),
                luminanceWeights
            );
            float luminanceBottom = dot(
                cameraLinearAt(displayUv - vec2(0.0, sampleStep.y)),
                luminanceWeights
            );
            float luminanceTop = dot(
                cameraLinearAt(displayUv + vec2(0.0, sampleStep.y)),
                luminanceWeights
            );
            float neighborhoodLuminance = 0.25 * (
                luminanceLeft + luminanceRight + luminanceBottom + luminanceTop
            );
            vec2 illuminationGradient = clamp(
                vec2(
                    luminanceRight - luminanceLeft,
                    luminanceTop - luminanceBottom
                ) * 4.5,
                vec2(-0.7),
                vec2(0.7)
            );
            vec3 lightDirection = normalize(vec3(illuminationGradient, 0.86));
            vec3 halfDirection = normalize(lightDirection + vec3(0.0, 0.0, 1.0));

            float positiveCameraDetail = max(cameraLuminance - neighborhoodLuminance, 0.0);
            float suppressedHighlight = positiveCameraDetail *
                (1.0 - materialParams.highlightRetention) * coverage;
            float materialLuminance = max(cameraLuminance - suppressedHighlight, 0.0001);
            float pigmentLuminance = max(dot(materialParams.pigmentColor, luminanceWeights), 0.0001);
            vec3 luminancePreservingPigment = materialParams.pigmentColor *
                (materialLuminance / pigmentLuminance);
            vec3 pigmented = mix(cameraLinear, luminancePreservingPigment, coverage);

            float roughness = clamp(materialParams.roughness, 0.08, 1.0);
            float specularPower = mix(112.0, 9.0, roughness * roughness);
            float normalLobe = pow(
                max(dot(lipNormal, halfDirection), 0.0),
                specularPower
            );
            float nativeHighlight = smoothstep(0.006, 0.075, positiveCameraDetail);
            float illuminationConfidence = smoothstep(
                0.008,
                0.11,
                length(illuminationGradient)
            );
            float cameraAnchoredLobe = normalLobe * mix(
                0.18,
                1.0,
                max(nativeHighlight, illuminationConfidence)
            );
            float arcCenter = sin(3.14159265 * clamp(lipUv.y, 0.0, 1.0));
            float innerEdge = smoothstep(0.56, 0.88, lipUv.x) *
                (1.0 - smoothstep(0.93, 1.0, lipUv.x)) * arcCenter;
            float wetEdgeGain = 1.0 +
                materialParams.wetInnerEdgeStrength * innerEdge * 2.2;
            float retainedNativeSpecular = positiveCameraDetail *
                materialParams.highlightRetention * 0.55;
            float reconstructedSpecular = materialParams.specularStrength * coverage *
                wetEdgeGain * (0.045 * cameraAnchoredLobe + retainedNativeSpecular);

            float textureDetail = cameraLuminance - neighborhoodLuminance;
            float textureCorrection = textureDetail * materialParams.microTextureRetention *
                coverage * 0.055;
            vec3 chromaDirection = pigmented / max(
                dot(pigmented, luminanceWeights),
                0.0001
            );
            pigmented += chromaDirection * textureCorrection;
            pigmented += vec3(1.0, 0.94, 0.92) * reconstructedSpecular;
            material.baseColor = vec4(max(pigmented, vec3(0.0)), 1.0);
        }
    """
}
