package com.example.armakeup.arcore

/** Face-local pigment absorption, retaining the paired camera's skin light and texture. */
internal object BlushMaterial {
    const val DEPTH_VERTEX_SHADER = """
        attribute vec4 aPosition;
        void main() { gl_Position = aPosition; }
    """
    const val DEPTH_FRAGMENT_SHADER = """
        precision mediump float;
        void main() { gl_FragColor = vec4(0.0); }
    """
    const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec3 aCheek;
        uniform vec4 uContour[24];
        varying vec3 vCheek;
        varying vec3 vCheekCore;
        void main() {
            gl_Position = aPosition;
            vCheek = aCheek;
            // Uniform per cheek. Compute the protected body on vertices rather
            // than repeating two contour walks for every covered fragment.
            bool left = aCheek.x < 0.0;
            vec2 previous = left ? uContour[23].xy : uContour[23].zw;
            vec2 moment = vec2(0.0);
            float area = 0.0;
            for (int i = 0; i < 24; i++) {
                vec2 current = left ? uContour[i].xy : uContour[i].zw;
                float cross = previous.x * current.y - current.x * previous.y;
                area += cross;
                moment += (previous + current) * cross;
                previous = current;
            }
            vec2 centre = moment / (3.0 * (abs(area) > 0.0000000001 ? area : 1.0));
            previous = left ? uContour[23].xy : uContour[23].zw;
            float radiusSquared = 1.0;
            bool inside = false;
            for (int i = 0; i < 24; i++) {
                vec2 current = left ? uContour[i].xy : uContour[i].zw;
                vec2 edge = current - previous;
                vec2 offset = centre - previous;
                float t = clamp(dot(offset, edge) / max(dot(edge, edge), 0.0000000001), 0.0, 1.0);
                vec2 nearest = offset - edge * t;
                radiusSquared = min(radiusSquared, dot(nearest, nearest));
                float dy = abs(edge.y) < 0.0000001 ? 0.0000001 : edge.y;
                float crossing = previous.x + (centre.y - previous.y) * edge.x / dy;
                if (((current.y > centre.y) != (previous.y > centre.y)) && centre.x < crossing) inside = !inside;
                previous = current;
            }
            vCheekCore = vec3(centre, inside ? sqrt(radiusSquared) : 0.0);
        }
    """
    const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        // Temporary contour inspection. Set false to restore the preserved feather exactly.
        const bool DEBUG_HARD_BOUNDARY = false;
        uniform samplerExternalOES uCamera;
        uniform vec2 uViewport;
        uniform vec2 uUvBottomLeft;
        uniform vec2 uUvBottomRight;
        uniform vec2 uUvTopLeft;
        uniform vec2 uUvTopRight;
        uniform vec3 uColor;
        uniform float uOpacity;
        // Left boundary in xy, right in zw; immutable with the presented camera pair.
        uniform vec4 uContour[24];
        uniform vec4 uContourParams;
        uniform vec4 uContourBounds[2];
        varying vec3 vCheek;
        varying vec3 vCheekCore;
        vec3 toLinear(vec3 c) {
            return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
        }
        vec3 toSrgb(vec3 c) {
            return mix(c * 12.92, 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
                step(vec3(0.0031308), c));
        }
        vec3 blushCameraAt(vec2 displayUv) {
            vec2 uv = clamp(displayUv, vec2(0.0), vec2(1.0));
            vec2 top = mix(uUvTopLeft, uUvTopRight, uv.x);
            vec2 bottom = mix(uUvBottomLeft, uUvBottomRight, uv.x);
            return texture2D(uCamera, mix(top, bottom, uv.y)).rgb;
        }
        vec3 softBlushCamera(vec2 displayUv, vec3 camera) {
            // Same bounded Gaussian low pass as the liner, for lighting only.
            float radius = max(2.0, min(uViewport.x, uViewport.y) * 0.012);
            vec2 dx = vec2(radius / uViewport.x, 0.0);
            vec2 dy = vec2(0.0, radius / uViewport.y);
            vec3 soft = camera * 0.25;
            soft += (blushCameraAt(displayUv + dx) + blushCameraAt(displayUv - dx) +
                blushCameraAt(displayUv + dy) + blushCameraAt(displayUv - dy)) * 0.125;
            soft += (blushCameraAt(displayUv + dx + dy) + blushCameraAt(displayUv + dx - dy) +
                blushCameraAt(displayUv - dx + dy) + blushCameraAt(displayUv - dx - dy)) * 0.0625;
            return soft;
        }
        vec3 cameraAwareBlushColor(vec3 softCamera, out float valueResponse) {
            vec3 source = clamp(uColor, 0.0, 1.0);
            valueResponse = 1.0;
            float sourceValue = max(source.r, max(source.g, source.b));
            if (sourceValue <= 0.52) return source;
            float sourceMinimum = min(source.r, min(source.g, source.b));
            float saturation = (sourceValue - sourceMinimum) / max(sourceValue, 0.001);
            // Neutral pigments must not acquire a grey absorption veil.
            float chromaGate = smoothstep(0.08, 0.22, saturation);
            if (chromaGate <= 0.0) return source;
            float lightPigment = smoothstep(0.52, 0.78, sourceValue);
            float vividPigment = 0.45 * smoothstep(0.52, 0.60, sourceValue) *
                smoothstep(0.55, 0.85, saturation);
            float correction = max(lightPigment, vividPigment) * chromaGate;
            float cameraValue = max(softCamera.r, max(softCamera.g, softCamera.b));
            // Retain gentle light adaptation without adding a dark absorption veil.
            valueResponse = mix(1.0, clamp(cameraValue / 0.85, 0.85, 1.0), 0.10 * correction);
            valueResponse *= 1.0 - 0.10 * correction;
            // Preserve hue: no camera skin RGB is mixed into the source pigment.
            return mix(source, vec3(sourceValue), 0.18 * correction);
        }
        void main() {
            vec2 p = vCheek.xy;
            bool left = p.x < 0.0;
            float enabledCheek = left ? uContourParams.z : uContourParams.w;
            if (enabledCheek < 0.5 || uOpacity <= 0.0) discard;
            float feather = max(left ? uContourParams.x : uContourParams.y, 0.0001);
            float blurRadius = feather * 1.8;
            float rimBlurRadius = blurRadius * 1.45;
            // The original (2t-t*t)^2 mask reaches 50% at t=0.4588039.
            // Keep that edge fixed while spreading the transition to both sides.
            float midpoint = feather * 0.4588039;
            float softTail = DEBUG_HARD_BOUNDARY ? 0.0 : rimBlurRadius - midpoint;
            vec4 bounds = left ? uContourBounds[0] : uContourBounds[1];
            if (any(lessThan(p, bounds.xy - softTail)) ||
                any(greaterThan(p, bounds.zw + softTail))) discard;
            vec2 previous = left ? uContour[23].xy : uContour[23].zw;
            float distanceSquared = 1.0;
            bool inside = false;
            for (int i = 0; i < 24; i++) {
                vec2 current = left ? uContour[i].xy : uContour[i].zw;
                vec2 edge = current - previous;
                vec2 offset = p - previous;
                float t = clamp(dot(offset, edge) / max(dot(edge, edge), 0.0000000001), 0.0, 1.0);
                vec2 nearest = offset - edge * t;
                distanceSquared = min(distanceSquared, dot(nearest, nearest));
                float dy = abs(edge.y) < 0.0000001 ? 0.0000001 : edge.y;
                float crossing = previous.x + (p.y - previous.y) * edge.x / dy;
                if (((current.y > p.y) != (previous.y > p.y)) && p.x < crossing) inside = !inside;
                previous = current;
            }
            if (DEBUG_HARD_BOUNDARY && !inside) discard;
            float signedDistance = (inside ? 1.0 : -1.0) * sqrt(distanceSquared);
            float blend = clamp(0.5 + (signedDistance - midpoint) / (2.0 * blurRadius), 0.0, 1.0);
            // Widen only the rim around its existing 50% edge. Keep the original
            // body profile below; the core weight protects the centre exactly.
            float rimBlend = clamp(0.5 + (signedDistance - midpoint) / (2.0 * rimBlurRadius), 0.0, 1.0);
            float side = 2.0 * abs(rimBlend - 0.5);
            float tail = 1.0 - side;
            float shoulder = side * tail * tail * tail * (1.0 + 3.0 * side);
            float softerBlend = 0.5 + sign(rimBlend - 0.5) * 0.5 * (side - 0.65 * shoulder);
            float rimWeight = 0.0;
            if (vCheekCore.z > 0.0001) {
                float radial = clamp((length(p - vCheekCore.xy) / vCheekCore.z - 0.55) / 0.45, 0.0, 1.0);
                rimWeight = radial * radial * radial * (10.0 + radial * (6.0 * radial - 15.0));
            }
            blend = mix(blend, softerBlend, rimWeight);
            // Blur only pigment coverage. Preserve the nominal contour and full body;
            // captured pores, lighting and camera texture remain sharp.
            float featherCoverage = blend * blend * blend *
                (10.0 + blend * (6.0 * blend - 15.0));
            if (DEBUG_HARD_BOUNDARY) featherCoverage = 1.0;
            float coverage = featherCoverage * smoothstep(0.08, 0.40, vCheek.z);
            if (coverage <= 0.0001 || uOpacity <= 0.0) discard;
            vec2 displayUv = vec2(gl_FragCoord.x / uViewport.x, 1.0 - gl_FragCoord.y / uViewport.y);
            vec2 top = mix(uUvTopLeft, uUvTopRight, displayUv.x);
            vec2 bottom = mix(uUvBottomLeft, uUvBottomRight, displayUv.x);
            vec3 camera = texture2D(uCamera, mix(top, bottom, displayUv.y)).rgb;
            // Share the existing lighting samples with local highlight detection.
            vec3 softCamera = softBlushCamera(displayUv, camera);
            float valueResponse;
            vec3 pigment = toLinear(cameraAwareBlushColor(softCamera, valueResponse));
            float maximum = max(max(pigment.r, pigment.g), max(pigment.b, 0.001));
            // Pigment normalization removes a uniform RGB gain. Apply the bounded
            // value response to transmission so it actually affects this sheer material.
            vec3 transmission = pow(max(pigment / maximum, vec3(0.01)), vec3(0.34)) * valueResponse;
            vec3 base = toLinear(camera);
            vec3 material = base * transmission;
            // Preserve bright captured reflections; no synthetic white highlight or shimmer.
            float cameraValue = max(camera.r, max(camera.g, camera.b));
            float softValue = max(softCamera.r, max(softCamera.g, softCamera.b));
            float highlightContrast = max(cameraValue - softValue, 0.0);
            // Bright skin alone is not a specular highlight. Keep pigment visible
            // across exposure changes while preserving locally brighter reflections.
            float reflection = smoothstep(0.65, 0.95, cameraValue) *
                smoothstep(0.025, 0.12, highlightContrast);
            // 150% extends the same strength scale; the full amount stays below one.
            float amount = coverage * clamp(uOpacity, 0.0, 1.5) * 0.58 * (1.0 - 0.65 * reflection);
            // Add density only inside the preserved body. The C2 falloff ends
            // before the rim starts, keeping its colour and feather unchanged.
            float centerWeight = 0.0;
            if (vCheekCore.z > 0.0001) {
                float radial = clamp((length(p - vCheekCore.xy) / vCheekCore.z - 0.20) / 0.35, 0.0, 1.0);
                centerWeight = clamp(1.0 - radial * radial * radial * (10.0 + radial * (6.0 * radial - 15.0)), 0.0, 1.0);
            }
            float baseStrength = clamp(uOpacity, 0.0, 1.5) * 0.58;
            float centerExtra = (1.0 - baseStrength) * 0.75 * centerWeight * clamp(uOpacity, 0.0, 1.0);
            amount += coverage * centerExtra * (1.0 - 0.65 * reflection);
            gl_FragColor = vec4(toSrgb(mix(base, material, amount)), 1.0);
        }
    """
}
