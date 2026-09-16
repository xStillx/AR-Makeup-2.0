package com.example.armakeup.arcore

/**
 * One shared dense pigment base for every finish. Finish changes only the camera-derived
 * reflection layer: none for matte, restrained for satin and full for gloss.
 */
internal object UnifiedLipMaterial {
    const val FRAGMENT_FUNCTION = """

            vec4 renderUnifiedLip(float coverage) {
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

                float baseLuminance = parityLuminance(base);
                float blurredLuminance = max(parityLuminance(blurred), 0.055);
                float logDetail = (log2(max(baseLuminance, 0.04)) -
                    log2(max(blurredLuminance, 0.04))) * uTuningCameraDetail *
                    uTuningSurfaceDetail;
                float detailStrength = 1.75 * uTuningMaterialDetail;
                float detailExponent =
                    clamp(min(logDetail, 0.0), -0.14, 0.0) * detailStrength *
                        1.08 * uTuningShadowStrength +
                    clamp(max(logDetail, 0.0), 0.0, 0.09) * detailStrength *
                        0.72 * uTuningMicroTexture;

                float cornerPosition = clamp(abs(vSatinUv.x - 0.5) * 2.0, 0.0, 1.0);
                float cornerRegion = smoothstep(0.62, 0.96, cornerPosition);
                float innerSeamRegion = 1.0 - smoothstep(0.018, 0.085, abs(vSatinUv.y - 0.50));
                float localShadow = 1.0 - smoothstep(0.16, 0.42, blurredLuminance);
                float openMouth = uSatinApertureVisibility;
                float apertureClosure = 1.0 - openMouth;
                float seamShadow = 1.0 - innerSeamRegion * smoothstep(0.30, 0.56, openMouth) *
                    (0.020 + localShadow * 0.08 * uTuningShadowStrength) * uTuningSeamShadow;
                vec3 opaquePigment = clamp(cameraValuePigment(base, uPigment) *
                    exp2(detailExponent) * seamShadow, 0.0, 1.0);
                vec3 naturalLipColor = clamp(blurred + (base - blurred) * 0.35, 0.0, 1.0);
                vec3 pigment = mix(naturalLipColor, opaquePigment,
                    clamp(uTuningPigmentOpacity, 0.0, 1.0));

                float maximum = max(base.r, max(base.g, base.b));
                float minimum = min(base.r, min(base.g, base.b));
                float saturation = maximum > 0.001 ? (maximum - minimum) / maximum : 0.0;
                float toothGuard = clamp(smoothstep(0.64, 0.82, baseLuminance) *
                    (1.0 - smoothstep(0.16, 0.30, saturation)) *
                    smoothstep(0.24, 0.46, openMouth) * innerSeamRegion *
                    uTuningToothProtection, 0.0, 1.0);

                bool isGloss = uFinishMode > 0.5 && uFinishMode < 1.5;
                bool isSatin = uFinishMode > 1.5;
                float finishReflection = isGloss ? 1.0 : (isSatin ? 0.46 : 0.0);
                float highlightGain = finishReflection * uTuningHighlightStrength *
                    uTuningSpecular * uTuningHighlightRetention;
                float highlightAmount = 0.0;
                if (highlightGain > 0.001) {
                    // Same camera signal for gloss and satin. Satin uses a smaller, weaker response.
                    float sampleScale = isSatin ? 0.78 : 1.0;
                    vec2 hx = uSatinSourceTexelX * 10.0 * uTuningCameraSampleScale *
                        uTuningHighlightSize * sampleScale;
                    vec2 hy = uSatinSourceTexelY * 7.0 * uTuningCameraSampleScale *
                        uTuningHighlightSize * sampleScale;
                    vec3 highlightSurround = (
                        cameraSrgbAt(vDisplayUv + hx) + cameraSrgbAt(vDisplayUv - hx) +
                        cameraSrgbAt(vDisplayUv + hy) + cameraSrgbAt(vDisplayUv - hy)
                    ) * 0.25;
                    float surroundLuminance = max(parityLuminance(highlightSurround), 0.04);
                    float highlightLogContrast = log2(max(baseLuminance, 0.04) / surroundLuminance);
                    float realHighlightSignal = smoothstep(
                        0.010 * uTuningHighlightThreshold,
                        0.135 * uTuningHighlightThreshold,
                        highlightLogContrast
                    ) * smoothstep(0.08, 0.58, baseLuminance);
                    float concentration = max(
                        (isSatin ? 1.65 : 1.20) * uTuningHighlightConcentration /
                            max(uTuningRoughness, 0.35),
                        1.0
                    );
                    float naturalHighlight = pow(clamp(realHighlightSignal, 0.0, 1.0), concentration);
                    float peakLimit = (isSatin ? 0.12 : 0.26) * uTuningHighlightStrength *
                        uTuningSpecular;
                    highlightAmount = min(
                        naturalHighlight * (0.10 + max(baseLuminance - surroundLuminance, 0.0)) *
                            1.35 * highlightGain,
                        peakLimit
                    );
                    highlightAmount *= (1.0 - clamp(cornerRegion * 0.82 *
                        uTuningCornerFade, 0.0, 1.0)) *
                        (1.0 - innerSeamRegion * mix(0.38, 0.66, apertureClosure)) *
                        (1.0 - toothGuard * 0.96);
                    highlightAmount *= 1.0 + (uTuningWetInnerEdge - 1.0) * innerSeamRegion *
                        openMouth * 0.22;
                }
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

                // Reflections belong to the surface and must not be changed by
                // the base-pigment luminance correction.
                pigment = mix(pigment, vec3(1.0), clamp(highlightAmount, 0.0, 0.96));

                float cornerOpacity = 1.0 - smoothstep(0.82, 0.98, cornerPosition) *
                    0.12 * uTuningCornerFade;

                // Full coverage reproduces the material color exactly in the lip interior.
                // Edge softness still comes from the coverage mask and corner opacity.
                float alpha = clamp(coverage * cornerOpacity, 0.0, 1.0);
                vec3 composited = mix(iosSrgbToLinear(base), iosSrgbToLinear(pigment), alpha);
                return vec4(clamp(iosLinearToSrgb(composited), 0.0, 1.0), alpha);
            }

    """
}