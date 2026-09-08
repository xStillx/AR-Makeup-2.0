package com.example.armakeup.arcore

import com.example.armakeup.makeup.LipMeshTessellator
import com.example.armakeup.makeup.ReferenceMatteLipstickProfile

/**
 * Port of the supplied iOS Metal compositor: satin / high density / creamy / B8202D.
 * Canonical UVs affect material shading only; the accepted mesh and coverage stay unchanged.
 * Source: Virtual Makeup/{MetalLipColorCompositor,LipstickTypes,CanonicalLipGeometry}.swift.
 */
internal object IosSatinLipMaterial {
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

            // SceneKit receives a tagged sRGB diffuse image and composites in linear light.
            // These IEC sRGB transfer functions are separate from the legacy gloss approximation.
            vec3 satinSrgbToLinear(vec3 color) {
                return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)),
                    step(vec3(0.04045), color));
            }

            vec3 satinLinearToSrgb(vec3 color) {
                color = max(color, vec3(0.0));
                return mix(color * 12.92, 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055,
                    step(vec3(0.0031308), color));
            }

            vec4 renderIosSatin(float coverage) {
                vec3 base = cameraSrgbAt(vDisplayUv);
                vec2 dx = uSatinSourceTexelX * 5.0 * uTuningCameraSampleScale;
                vec2 dy = uSatinSourceTexelY * 5.0 * uTuningCameraSampleScale;
                vec3 blurred = base * 4.0;
                blurred += cameraSrgbAt(vDisplayUv + dx);
                blurred += cameraSrgbAt(vDisplayUv - dx);
                blurred += cameraSrgbAt(vDisplayUv + dy);
                blurred += cameraSrgbAt(vDisplayUv - dy);
                blurred += cameraSrgbAt(vDisplayUv + dx + dy);
                blurred += cameraSrgbAt(vDisplayUv - dx - dy);
                blurred += cameraSrgbAt(vDisplayUv + dx - dy);
                blurred += cameraSrgbAt(vDisplayUv - dx + dy);
                blurred /= 12.0;
                vec2 hx = uSatinSourceTexelX * 12.0 * uTuningCameraSampleScale *
                    uTuningHighlightSize;
                vec2 hy = uSatinSourceTexelY * 8.0 * uTuningCameraSampleScale *
                    uTuningHighlightSize;
                vec3 highlightSurround = (
                    cameraSrgbAt(vDisplayUv + hx) + cameraSrgbAt(vDisplayUv - hx) +
                    cameraSrgbAt(vDisplayUv + hy) + cameraSrgbAt(vDisplayUv - hy)
                ) * 0.25;

                float baseLuminance = parityLuminance(base);
                float blurredLuminance = max(parityLuminance(blurred), 0.055);
                float localLighting = smoothstep(0.10, 0.55, blurredLuminance);
                // Match iOS's neutral fallback without inventing ARKit ambient intensity.
                // No global low-light multiplier until an equivalent light estimate is available.
                float combinedLighting = clamp(mix(1.0, localLighting, 0.25), 0.45, 1.0);
                float brightScene = smoothstep(0.78, 1.0, combinedLighting);
                float logDetail = (log2(max(baseLuminance, 0.04)) -
                    log2(max(blurredLuminance, 0.04))) * uTuningCameraDetail *
                    uTuningSurfaceDetail;
                // Metal satin detailStrength * creamy.detailResponse.
                float detailStrength = 1.7 * 0.92 * mix(1.0, 0.45, brightScene) *
                    uTuningMaterialDetail;
                float detailExponent = clamp(min(logDetail, 0.0), -0.14, 0.0) * detailStrength * 1.30 *
                    uTuningShadowStrength +
                    clamp(max(logDetail, 0.0), 0.0, 0.10) * detailStrength * 0.92 *
                    uTuningMicroTexture;
                float cornerPosition = clamp(abs(vSatinUv.x - 0.5) * 2.0, 0.0, 1.0);
                float cornerRegion = smoothstep(0.62, 0.96, cornerPosition);
                float innerSeamRegion = 1.0 - smoothstep(0.018, 0.085, abs(vSatinUv.y - 0.50));
                float localShadow = 1.0 - smoothstep(0.16, 0.42, blurredLuminance);
                float openMouth = uSatinApertureVisibility;
                float apertureClosure = 1.0 - openMouth;
                float seamShadow = 1.0 - innerSeamRegion * smoothstep(0.30, 0.56, openMouth) *
                    (0.020 + localShadow * 0.08 * uTuningShadowStrength) * uTuningSeamShadow;
                vec3 pigment = clamp(uPigment * exp2(detailExponent) * seamShadow, 0.0, 1.0);
                float maximum = max(base.r, max(base.g, base.b));
                float minimum = min(base.r, min(base.g, base.b));
                float saturation = maximum > 0.001 ? (maximum - minimum) / maximum : 0.0;
                float toothGuard = clamp(smoothstep(0.64, 0.82, baseLuminance) *
                    (1.0 - smoothstep(0.16, 0.30, saturation)) *
                    smoothstep(0.24, 0.46, openMouth) * innerSeamRegion *
                    uTuningToothProtection, 0.0, 1.0);

                float surroundLuminance = max(parityLuminance(highlightSurround), 0.04);
                float relativeHighlight = smoothstep(0.035 * uTuningHighlightThreshold,
                    0.22 * uTuningHighlightThreshold,
                    log2(max(baseLuminance, 0.04) / surroundLuminance));
                float highlightBrightness = smoothstep(0.18, 0.68, baseLuminance);
                float highlightDifference = max(baseLuminance - surroundLuminance, 0.0);
                float naturalHighlight = pow(clamp(relativeHighlight * highlightBrightness, 0.0, 1.0),
                    1.15 * uTuningHighlightConcentration / max(uTuningRoughness, 0.35));
                // Metal satin highlightStrength * creamy.highlightResponse; high density.
                float highlightAmount = min(naturalHighlight * (0.07 + highlightDifference * 0.90) *
                    (1.16 * 1.32) * uTuningHighlightStrength * uTuningSpecular *
                    uTuningHighlightRetention * uTuningSatinGlow,
                    0.48 * uTuningHighlightStrength * uTuningSpecular * uTuningSatinGlow);
                highlightAmount *= (1.0 - clamp(cornerRegion * 0.82 *
                    uTuningCornerFade, 0.0, 1.0)) *
                    (1.0 - innerSeamRegion * mix(0.74, 0.92, apertureClosure)) *
                    (1.0 - toothGuard * 0.96);
                highlightAmount *= 1.0 + (uTuningWetInnerEdge - 1.0) * innerSeamRegion *
                    openMouth * 0.22;
                pigment = mix(pigment, vec3(1.0), clamp(highlightAmount, 0.0, 0.96));
                pigment = mix(pigment, base * (1.0 - localShadow * 0.05), toothGuard * 0.94);

                float pigmentLuminance = parityLuminance(pigment);
                float luminanceDelta = (uTuningLuminancePreservation - 1.0) *
                    (baseLuminance - pigmentLuminance) * 0.60;
                pigment = parityColorWithLuminance(pigment, clamp(
                    pigmentLuminance + luminanceDelta, 0.0, 1.0));
                pigmentLuminance = parityLuminance(pigment);
                float capturedCornerLuminance = min(baseLuminance, blurredLuminance);
                float cornerShadowEvidence = smoothstep(0.025, 0.18,
                    pigmentLuminance - capturedCornerLuminance);
                float retainedCornerLuminance = max(capturedCornerLuminance, pigmentLuminance * 0.58);
                pigment = parityColorWithLuminance(pigment, mix(pigmentLuminance, retainedCornerLuminance,
                    smoothstep(0.86, 1.0, cornerPosition) * cornerShadowEvidence * 0.76));
                float cornerOpacity = 1.0 - smoothstep(0.82, 0.98, cornerPosition) *
                    0.12 * uTuningCornerFade;
                // High-density compensationAlpha/outputCoverage = 0.92. SceneKit opacity = 0.99.
                // Compensate the local average only, preserving the captured fine relief.
                vec3 correctiveColor = clamp((pigment - blurred * 0.08) / 0.92, 0.0, 1.0);
                float alpha = coverage * 0.92 * 0.99 * cornerOpacity;
                vec3 composited = mix(satinSrgbToLinear(base), satinSrgbToLinear(correctiveColor), alpha);
                return vec4(clamp(satinLinearToSrgb(composited), 0.0, 1.0), 1.0);
            }

    """
}
