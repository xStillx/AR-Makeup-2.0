package com.example.armakeup.arcore

import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.LipstickFinish
import com.example.armakeup.makeup.LipstickDensity
import com.example.armakeup.makeup.LipstickTexture
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile

/**
 * iOS dev diffuse/shine equations (42318424), independently implemented in GLSL.
 * No temporal shine history/SDF compute passes; the accepted Android annulus gates highlights.
 * Canonical UVs affect material shading only; the accepted mesh and coverage stay unchanged.
 * Reference: github.com/Dzhanaeva/virtual-makeup, dev commit 42318424b4655ecb6f724cb8395b7d72e42942f5.
 * HSV camera-value idea originates in Banuba beauty-ios; no SDK/models/assets are imported.
 */
internal object IosLipMaterial {
    data class FinishParameters(
        val detailStrength: Float,
        val highlightStrength: Float,
        val highlightMaximum: Float,
        val highlightConcentration: Float,
    )

    fun parameters(finish: LipstickFinish, texture: LipstickTexture): FinishParameters {
        val detail = if (finish == LipstickFinish.SATIN) 1.7f else 1.9f
        // User calibration: reduce the reflection layer, not the diffuse pigment,
        // its camera-value transfer, or the spatial mask of the captured highlight.
        val reflectionScale = when (finish) {
            LipstickFinish.SATIN -> 0.40f
            LipstickFinish.GLOSS, LipstickFinish.OIL -> 0.50f
            else -> 1f
        }
        val highlight = when (finish) {
            LipstickFinish.SATIN -> 1.35f
            LipstickFinish.GLOSS, LipstickFinish.OIL -> 1.40f
            else -> 0f
        }
        val maximum = when (finish) {
            LipstickFinish.SATIN -> 0.70f
            LipstickFinish.GLOSS -> 0.40f
            LipstickFinish.OIL -> 0.50f
            else -> 0f
        }
        return FinishParameters(
            detail * texture.detailResponse,
            highlight * texture.highlightResponse * reflectionScale,
            maximum * texture.highlightLimitResponse * reflectionScale,
            texture.highlightConcentration,
        )
    }

    /** New dev density; never change the shared enum/UNIFIED initial-intensity rule. */
    fun coverage(density: LipstickDensity): Float = when (density) {
        LipstickDensity.LOW -> 0.57f
        LipstickDensity.MEDIUM -> 0.68f
        LipstickDensity.HIGH -> 1.00f
    }

    fun coverage(density: LipstickDensity, finish: LipstickFinish): Float {
        val base = coverage(density)
        return if (finish == LipstickFinish.TINT) base + (0.88f - base) * 0.65f else base
    }

    /** Membership, not canonical UV height: the tessellator emits upper then lower triangles. */
    fun lowerLipMask(tessellator: LipMeshTessellator): FloatArray =
        FloatArray(tessellator.vertexCount) { vertex ->
            if (vertex < tessellator.verticesPerLip) 0f else 1f
        }

    fun textureCoordinates(tessellator: LipMeshTessellator): FloatArray {
        val outer = floatArrayOf(
            0.08000000f, 0.49122993f,
            0.13026791f, 0.35517312f,
            0.20929948f, 0.26877977f,
            0.30368258f, 0.17462102f,
            0.40153170f, 0.12695815f,
            0.49991815f, 0.12000000f,
            0.59846830f, 0.12695815f,
            0.69631742f, 0.17462102f,
            0.79070052f, 0.26877977f,
            0.86973209f, 0.35517312f,
            0.92000000f, 0.49122993f,
            0.88289868f, 0.64046597f,
            0.80384220f, 0.73840651f,
            0.71427736f, 0.82011430f,
            0.60052514f, 0.88000000f,
            0.49991815f, 0.86131040f,
            0.39947486f, 0.88000000f,
            0.28572264f, 0.82011430f,
            0.19616135f, 0.73840651f,
            0.11710132f, 0.64046597f
        )
        val inner = floatArrayOf(
            0.15706014f, 0.49788097f,
            0.19111891f, 0.48518432f,
            0.24357887f, 0.48625481f,
            0.31731178f, 0.48625481f,
            0.40403691f, 0.48625481f,
            0.49991815f, 0.48625481f,
            0.59596309f, 0.48625481f,
            0.68268822f, 0.48625481f,
            0.75642113f, 0.48625481f,
            0.80888109f, 0.48518432f,
            0.84293986f, 0.49788097f,
            0.80821921f, 0.51180604f,
            0.74497687f, 0.51180604f,
            0.67016217f, 0.51180604f,
            0.58758629f, 0.51180604f,
            0.50008185f, 0.51196398f,
            0.41241371f, 0.51180604f,
            0.32983783f, 0.51180604f,
            0.25502313f, 0.51180604f,
            0.19178079f, 0.51180604f
        )
        // The same contour interpolation keeps reference UV knots aligned with rendered vertices.
        val reference = tessellator.tessellate(outer, inner,
            ReferenceMatteLipstickProfile.upper, ReferenceMatteLipstickProfile.lower)
        return FloatArray(tessellator.vertexCount * 2).also { output ->
            repeat(tessellator.vertexCount) { vertex ->
                output[vertex * 2] = reference[vertex * LipMeshTessellator.VERTEX_COMPONENT_COUNT]
                output[vertex * 2 + 1] = reference[vertex * LipMeshTessellator.VERTEX_COMPONENT_COUNT + 1]
            }
        }
    }

    const val FRAGMENT_FUNCTION = """

            // Shared transfer functions are deliberately unchanged: UNIFIED also uses them.
            vec3 iosSrgbToLinear(vec3 color) {
                return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)),
                    step(vec3(0.04045), color));
            }

            vec3 iosLinearToSrgb(vec3 color) {
                color = max(color, vec3(0.0));
                return mix(color * 12.92, 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055,
                    step(vec3(0.0031308), color));
            }

            float iosDevLuminance(vec3 linearColor) {
                return clamp(dot(linearColor, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
            }

            // Independent implementation of the dev diffuse equations. Do not use
            // cameraValuePigment: its partial-transfer override belongs to UNIFIED.
            vec3 iosDevPigment(vec3 hsv, float cameraValue) {
                hsv.z = clamp(cameraValue * hsv.z / 0.85, 0.0, 1.0);
                return iosSrgbToLinear(lipHsvToRgb(hsv));
            }

            vec4 renderIosLip(float coverage) {
                vec3 camera = cameraSrgbAt(vDisplayUv);
                vec3 linearCamera = iosSrgbToLinear(camera);
                vec3 pigmentHsv = lipRgbToHsv(clamp(uPigment, 0.0, 1.0));
                float cameraValue = max(camera.r, max(camera.g, camera.b));
                vec3 pigment = iosDevPigment(pigmentHsv, cameraValue);
                bool isGloss = uFinishMode > 0.5 && uFinishMode < 1.5;
                bool isSatin = uFinishMode > 1.5 && uFinishMode < 2.5;
                bool isOil = uFinishMode > 2.5 && uFinishMode < 3.5;
                bool isWet = isGloss || isOil;
                float finishGlow = isSatin ? uTuningSatinGlow : 1.0;
                float highlightGain = uIosHighlightStrength * uTuningHighlightStrength *
                    uTuningSpecular * uTuningHighlightRetention * finishGlow;
                float shine = 0.0;
                if (highlightGain > 0.001) {
                    vec2 hx = uSatinSourceTexelX * 12.0 * uTuningCameraSampleScale *
                        uTuningHighlightSize;
                    vec2 hy = uSatinSourceTexelY * 8.0 * uTuningCameraSampleScale *
                        uTuningHighlightSize;
                    // Average in linear RGB, as in dev, not in display RGB.
                    vec3 surround = (
                        iosSrgbToLinear(cameraSrgbAt(vDisplayUv + hx)) +
                        iosSrgbToLinear(cameraSrgbAt(vDisplayUv - hx)) +
                        iosSrgbToLinear(cameraSrgbAt(vDisplayUv + hy)) +
                        iosSrgbToLinear(cameraSrgbAt(vDisplayUv - hy))
                    ) * 0.25;
                    float luminance = iosDevLuminance(linearCamera);
                    float surroundLuminance = max(iosDevLuminance(surround), 0.0045);
                    float contrast = log2(max(luminance, 0.0031) / surroundLuminance) * 0.45;
                    float relativeHighlight = smoothstep(0.001 * uTuningHighlightThreshold,
                        0.065 * uTuningHighlightThreshold, contrast);
                    float brightnessGate = smoothstep(0.012, 0.220, luminance);
                    vec3 residual = max(linearCamera - surround, vec3(0.0));
                    float residualMaximum = max(residual.r, max(residual.g, residual.b));
                    float residualCommon = min(residual.r, min(residual.g, residual.b));
                    float illuminantEvidence = smoothstep(0.18, 0.62,
                        residualCommon / max(residualMaximum, 0.0001));
                    float signal = clamp(relativeHighlight * brightnessGate *
                        mix(0.30, 1.0, illuminantEvidence), 0.0, 1.0);
                    float shineMask = smoothstep(isOil ? 0.30 : (isGloss ? 0.34 : 0.37),
                        isOil ? 0.72 : (isGloss ? 0.78 : 0.89), signal);
                    if (isWet) {
                        // Remove the observed wet reflection from the diffuse value
                        // before applying it separately; otherwise it is counted twice.
                        vec3 surroundSrgb = iosLinearToSrgb(surround);
                        float surroundValue = max(surroundSrgb.r,
                            max(surroundSrgb.g, surroundSrgb.b));
                        float diffuseValue = mix(cameraValue, min(cameraValue, surroundValue),
                            shineMask * 0.82);
                        pigment = iosDevPigment(pigmentHsv, diffuseValue);
                    }
                    float exponent = (isOil ? 1.05 : (isGloss ? 1.30 : 1.20)) *
                        uTuningHighlightConcentration / max(uTuningRoughness, 0.35);
                    shine = min(pow(shineMask, max(exponent, 0.001)) *
                        (isOil ? 0.42 : (isGloss ? 0.24 : 0.26)) * highlightGain,
                        uIosHighlightMaximum * uTuningHighlightStrength *
                            uTuningSpecular * finishGlow);

                    float cornerPosition = clamp(abs(vSatinUv.x - 0.5) * 2.0, 0.0, 1.0);
                    float cornerRegion = smoothstep(0.62, 0.96, cornerPosition);
                    float seam = 1.0 - smoothstep(0.018, 0.085, abs(vSatinUv.y - 0.5));
                    float apertureClosure = 1.0 - uSatinApertureVisibility;
                    float maximum = max(camera.r, max(camera.g, camera.b));
                    float minimum = min(camera.r, min(camera.g, camera.b));
                    float saturation = maximum > 0.001 ? (maximum - minimum) / maximum : 0.0;
                    float tooth = smoothstep(0.64, 0.82, parityLuminance(camera)) *
                        (1.0 - smoothstep(0.16, 0.30, saturation)) *
                        smoothstep(0.24, 0.46, uSatinApertureVisibility) * seam *
                        uTuningToothProtection;
                    shine *= (1.0 - clamp(cornerRegion * 0.82 * uTuningCornerFade, 0.0, 1.0)) *
                        (1.0 - seam * (isWet ? mix(0.30, 0.58, apertureClosure) :
                            mix(0.74, 0.92, apertureClosure))) *
                        (1.0 - clamp(tooth * 0.96, 0.0, 1.0));
                    // Android's accepted annulus replaces iOS's separate SDF shine gate.
                    // This only gates reflections; never fade the inner pigment coverage.
                    shine *= smoothstep(0.12, 0.30, vLipUv.x) *
                        (1.0 - smoothstep(0.80, 0.98, vLipUv.x));
                    // Explicit tessellator membership identifies the lip independently
                    // of pose, mouth opening, or historical material UV orientation.
                    // Gate only the added reflection; retain diffuse camera shading
                    // and wet-highlight separation so no coloured hot spot is restored.
                    shine *= vLowerLip;
                }

                if (isWet) {
                    pigment = mix(pigment, vec3(1.0),
                        clamp(shine * (isOil ? 2.30 : 1.45), 0.0, 1.0));
                } else if (isSatin) {
                    vec3 materialHsv = lipRgbToHsv(iosLinearToSrgb(pigment));
                    float visibleShine = clamp(shine * 1.40, 0.0, 1.0);
                    materialHsv.y *= 1.0 - visibleShine * 0.22;
                    materialHsv.z = mix(materialHsv.z, 1.0, visibleShine);
                    pigment = iosSrgbToLinear(lipHsvToRgb(materialHsv));
                }

                // Optional colour controls modify material, never the camera backdrop.
                // Opacity/intensity belong to alpha, not an extra display-RGB colour mix.
                vec3 material = iosLinearToSrgb(pigment);
                material = mix(vec3(parityLuminance(material)), material, uTuningSaturation);
                material = tuneHue(material, uTuningHueDegrees);
                material = (material - vec3(0.5)) * uTuningContrast + vec3(0.5);
                material = clamp(material * uTuningBrightness, 0.0, 1.0);
                pigment = mix(iosSrgbToLinear(material), linearCamera,
                    clamp(uTuningNaturalLipBlend, 0.0, 1.0));
                float alpha = clamp(coverage * uIosCoreCoverage *
                    uTuningPigmentOpacity * uTuningOpacity, 0.0, 1.0);
                return vec4(iosLinearToSrgb(pigment), alpha);
            }

    """
}
