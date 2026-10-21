package com.example.armakeup.arcore

/** Continuous lip-side pigment falloff with weak low-frequency camera light, no added detail. */
internal object LipLinerMaterial {
    const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec4 aLinerData;
        varying vec4 vLinerData;
        varying vec2 vDisplayUv;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vLinerData = aLinerData;
            vDisplayUv = vec2(aPosition.x * 0.5 + 0.5, 0.5 - aPosition.y * 0.5);
        }
    """
    const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec4 vLinerData;
        varying vec2 vDisplayUv;
        uniform samplerExternalOES uCamera;
        uniform vec2 uUvBottomLeft;
        uniform vec2 uUvBottomRight;
        uniform vec2 uUvTopLeft;
        uniform vec2 uUvTopRight;
        uniform vec2 uLightStepX;
        uniform vec2 uLightStepY;
        uniform vec3 uColor;
        uniform float uOpacity;
        uniform float uAntialias;
        uniform float uThickness;
        uniform float uBlur;
        // Scale the stroke around the measured contour without widening the outer silhouette.
        float linerWidthScale() { return 0.65 * clamp(uThickness, 0.25, 2.0); }
        vec3 cameraAt(vec2 displayUv) {
            vec2 uv = clamp(displayUv, vec2(0.0), vec2(1.0));
            vec2 top = mix(uUvTopLeft, uUvTopRight, uv.x);
            vec2 bottom = mix(uUvBottomLeft, uUvBottomRight, uv.x);
            return texture2D(uCamera, mix(top, bottom, uv.y)).rgb;
        }
        vec3 softCameraColor() {
            // Spatial Gaussian low pass only: never reintroduce a high-frequency residual.
            vec3 soft = cameraAt(vDisplayUv) * 0.25;
            soft += (cameraAt(vDisplayUv + uLightStepX) + cameraAt(vDisplayUv - uLightStepX) +
                cameraAt(vDisplayUv + uLightStepY) + cameraAt(vDisplayUv - uLightStepY)) * 0.125;
            soft += (cameraAt(vDisplayUv + uLightStepX + uLightStepY) +
                cameraAt(vDisplayUv + uLightStepX - uLightStepY) +
                cameraAt(vDisplayUv - uLightStepX + uLightStepY) +
                cameraAt(vDisplayUv - uLightStepX - uLightStepY)) * 0.0625;
            return soft;
        }
        vec3 cameraAwarePigment() {
            vec3 soft = softCameraColor();
            float light = dot(soft, vec3(0.2126, 0.7152, 0.0722));
            // Bounded 25% light transfer: -8.75% to +2.5% pigment value, not camera RGB.
            float gain = mix(1.0, clamp(light / 0.45, 0.65, 1.10), 0.25);
            vec3 pigment = clamp(uColor * gain, 0.0, 1.0);
            float sourceValue = max(uColor.r, max(uColor.g, uColor.b));
            // Protect accepted dark pigments, including terracotta #843F33, exactly.
            if (sourceValue <= 0.52) return pigment;
            float sourceMinimum = min(uColor.r, min(uColor.g, uColor.b));
            float sourceSaturation = (sourceValue - sourceMinimum) / max(sourceValue, 0.001);
            float lightPigment = smoothstep(0.52, 0.78, sourceValue);
            float vividPigment = 0.45 * smoothstep(0.52, 0.60, sourceValue) *
                smoothstep(0.55, 0.85, sourceSaturation);
            float correction = max(lightPigment, vividPigment);
            float cameraValue = max(soft.r, max(soft.g, soft.b));
            float valueResponse = mix(1.0, clamp(cameraValue / 0.85, 0.60, 1.0), 0.35 * correction);
            valueResponse *= 1.0 - 0.10 * correction;
            // Reduce HSV saturation without rotating hue; do not mix camera skin RGB into pigment.
            float pigmentValue = max(pigment.r, max(pigment.g, pigment.b));
            vec3 muted = mix(pigment, vec3(pigmentValue), 0.18 * correction);
            return muted * valueResponse;
        }
        float pigmentAt(float distance, float depth) {
            if (distance >= depth) return 0.0;
            float widthScale = linerWidthScale();
            float offset = distance;
            // Narrow exterior shoulder, longer inward fade; no stacked stroke plateaus.
            float sigma = (offset < 0.0 ? 2.4 : 7.0) * widthScale;
            float variance = sigma * sigma + uAntialias * uAntialias;
            float pigment = 0.94 * exp(-0.5 * offset * offset / variance);
            pigment *= 1.0 - smoothstep(18.0 * widthScale, 24.0 * widthScale, max(offset, 0.0));
            float inner = 1.0 - smoothstep(0.88, 1.0, distance / depth);
            return pigment * inner;
        }
        float blurredPigmentAt(float distance, float depth, float blurStep) {
            // Fixed-radius kernels avoid creating a ridge where the blur radius changes.
            float mask = pigmentAt(distance, depth) * 0.375;
            mask += (pigmentAt(distance - blurStep, depth) +
                pigmentAt(distance + blurStep, depth)) * 0.25;
            mask += (pigmentAt(distance - blurStep * 2.0, depth) +
                pigmentAt(distance + blurStep * 2.0, depth)) * 0.0625;
            // Blur the stroke before clipping it to the lip. Blurring a pre-clipped half-stroke
            // and re-clipping by the blur radius moved its visible edge inside fuller lips.
            return mask;
        }
        void main() {
            float widthScale = linerWidthScale();
            float distance = vLinerData.x;
            float depth = max(vLinerData.y, 0.0001);
            float blurStep = max(1.4, uAntialias * 1.1);
            float baseMask = blurredPigmentAt(distance, depth, blurStep);
            float softCoreMask = blurredPigmentAt(distance, depth,
                blurStep * (1.0 + 2.0 * clamp(uBlur, 0.0, 2.0)));
            float inwardOffset = max(distance, 0.0);
            float contourWeight = 1.0 - smoothstep(0.0, 12.0 * widthScale, inwardOffset);
            // Soften only the contour/core, never add opacity or change the distant inward tail.
            float mask = mix(baseMask, min(baseMask, softCoreMask), contourWeight);
            float corner = smoothstep(0.0, 0.12, min(vLinerData.w, 1.0 - vLinerData.w));
            float inner = 1.0 - smoothstep(0.88, 1.0, vLinerData.z);
            // One viewport pixel of AA, independent of the selected blur and lip fullness.
            // The unexpanded mesh still excludes skin and the mouth aperture geometrically.
            float outer = smoothstep(0.0, uAntialias, distance);
            float alpha = clamp(mask * outer * inner * corner * uOpacity, 0.0, 1.0);
            if (alpha <= 0.0) {
                gl_FragColor = vec4(uColor, 0.0);
                return;
            }
            gl_FragColor = vec4(cameraAwarePigment(), alpha);
        }
    """
}
