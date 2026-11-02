package com.example.armakeup.arcore

/** Linear-light diffuse colour correction with captured high-frequency luminance. */
internal object FoundationMaterial {
    const val QUAD_VERTEX = """
        attribute vec2 aPosition;
        varying vec2 vUv;
        void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vUv = aPosition * 0.5 + 0.5; }
    """
    const val TRANSFER = """
        vec3 linearColor(vec3 c) {
            return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
        }
        vec3 srgbColor(vec3 c) {
            return mix(c * 12.92, 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
                step(vec3(0.0031308), c));
        }
        float luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
    """
    const val SOFT_FRAGMENT = """
        precision highp float;
        uniform sampler2D uCamera;
        uniform vec2 uRadius;
        varying vec2 vUv;
    """ + TRANSFER + """
        void main() {
            vec3 center = linearColor(texture2D(uCamera, vUv).rgb);
            vec3 sum = vec3(0.0); float weightSum = 0.0;
            for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                vec3 sampleColor = linearColor(texture2D(uCamera,
                    clamp(vUv + vec2(float(x), float(y)) * uRadius, 0.0, 1.0)).rgb);
                vec3 delta = sampleColor - center;
                // Edge-aware filtering is used for the diffuse base, never the final skin image.
                float spatial = (x == 0 ? 2.0 : 1.0) * (y == 0 ? 2.0 : 1.0);
                float weight = spatial * exp(-dot(delta, delta) / 0.018);
                sum += sampleColor * weight; weightSum += weight;
            }
            gl_FragColor = vec4(srgbColor(sum / max(weightSum, 0.001)), 1.0);
        }
    """
    const val COPY_FRAGMENT = """
        precision mediump float;
        uniform sampler2D uCamera;
        varying vec2 vUv;
        void main() { gl_FragColor = texture2D(uCamera, vUv); }
    """
    // Internal layer alpha carries the applied smoothing weight, not transparency.
    const val BASE_COPY_FRAGMENT = """
        precision mediump float;
        uniform sampler2D uCamera;
        varying vec2 vUv;
        void main() { gl_FragColor = vec4(texture2D(uCamera, vUv).rgb, 0.0); }
    """
    const val VERTEX = """
        attribute vec4 aPosition;
        attribute vec4 aSkin;
        varying vec4 vSkin;
        void main() { gl_Position = aPosition; vSkin = aSkin; }
    """
    const val FRAGMENT = """
        precision highp float;
        uniform sampler2D uCamera;
        uniform sampler2D uSoft;
        uniform sampler2D uRetouchMap;
        uniform float uRetouch;
        uniform vec2 uViewport;
        uniform vec2 uFeatureScale;
        uniform vec3 uColor;
        uniform float uCoverage;
        uniform float uLeftHalfOnly;
        uniform vec4 uComparisonPlane;
        uniform vec4 uBoundary[36];
        uniform vec2 uLips[20];
        uniform vec4 uEyes[16];
        uniform vec4 uBrows[10];
        uniform vec4 uNostrils;
        uniform vec3 uReferences[4];
        varying vec4 vSkin;
    """ + TRANSFER + SkinRetouchMaterial.SMOOTHING + """
        float segmentDistance(vec2 p, vec2 a, vec2 b) {
            vec2 edge = b - a;
            vec2 offset = p - a;
            return length(offset - edge * clamp(dot(offset, edge) / max(dot(edge, edge), 1e-12), 0.0, 1.0));
        }
        bool crossing(vec2 p, vec2 a, vec2 b) {
            float dy = b.y - a.y;
            if (abs(dy) < 1e-9) return false;
            return ((a.y > p.y) != (b.y > p.y)) &&
                p.x < a.x + (p.y - a.y) * (b.x - a.x) / dy;
        }
        void main() {
            if (uCoverage <= 0.0) discard;
            // Compare both pigment and retouch against the unmodified paired camera.
            // Face-local position follows pose; screen x would cut the nose in profile.
            if (uLeftHalfOnly > 0.5 && dot(uComparisonPlane.xyz, vSkin.xyz) + uComparisonPlane.w < 0.0) discard;
            vec2 p = (gl_FragCoord.xy / uViewport * 2.0 - 1.0) * uFeatureScale;
            // Continuous, uniform body. The mesh is only placement/depth, never lighting
            // or opacity. Evaluate the outer feather per fragment in measured 3D metres.
            float boundaryDistanceSquared = 1.0;
            vec4 boundaryPrevious = uBoundary[35];
            for (int i = 0; i < 36; i++) {
                vec3 edge = uBoundary[i].xyz - boundaryPrevious.xyz;
                vec3 offset = vSkin.xyz - boundaryPrevious.xyz;
                float t = clamp(dot(offset, edge) / max(dot(edge, edge), 1e-12), 0.0, 1.0);
                vec3 nearest = offset - edge * t;
                float feather = max(mix(boundaryPrevious.w, uBoundary[i].w, t), 0.001);
                boundaryDistanceSquared = min(boundaryDistanceSquared, dot(nearest, nearest) / (feather * feather));
                boundaryPrevious = uBoundary[i];
            }
            float mask = smoothstep(0.0, 1.0, sqrt(boundaryDistanceSquared));
            if (mask < 0.0001) discard;
            float lipDistance = 1.0; bool inLips = false;
            vec2 previous = uLips[19];
            for (int i = 0; i < 20; i++) {
                lipDistance = min(lipDistance, segmentDistance(p, previous, uLips[i]));
                if (crossing(p, previous, uLips[i])) inLips = !inLips;
                previous = uLips[i];
            }
            // Reach the measured lip contour without a fully unpainted skin band.
            // Keep the vermilion/mouth excluded; soften coverage only on the skin side.
            mask *= inLips ? 0.0 : smoothstep(0.0, 0.0035, lipDistance);
            float leftDistance = 1.0; float rightDistance = 1.0;
            bool inLeft = false; bool inRight = false;
            vec4 prevEye = uEyes[15];
            for (int i = 0; i < 16; i++) {
                vec4 eye = uEyes[i];
                leftDistance = min(leftDistance, segmentDistance(p, prevEye.xy, eye.xy));
                rightDistance = min(rightDistance, segmentDistance(p, prevEye.zw, eye.zw));
                if (crossing(p, prevEye.xy, eye.xy)) inLeft = !inLeft;
                if (crossing(p, prevEye.zw, eye.zw)) inRight = !inRight;
                prevEye = eye;
            }
            mask *= (inLeft || inRight) ? 0.0 : smoothstep(0.001, 0.004, min(leftDistance, rightDistance));
            float leftBrowDistance = 1.0; float rightBrowDistance = 1.0;
            bool inLeftBrow = false; bool inRightBrow = false;
            vec4 prevBrow = uBrows[9];
            for (int i = 0; i < 10; i++) {
                vec4 brow = uBrows[i];
                leftBrowDistance = min(leftBrowDistance, segmentDistance(p, prevBrow.xy, brow.xy));
                rightBrowDistance = min(rightBrowDistance, segmentDistance(p, prevBrow.zw, brow.zw));
                if (crossing(p, prevBrow.xy, brow.xy)) inLeftBrow = !inLeftBrow;
                if (crossing(p, prevBrow.zw, brow.zw)) inRightBrow = !inRightBrow;
                prevBrow = brow;
            }
            float signedBrowDistance = min((inLeftBrow ? -1.0 : 1.0) * leftBrowDistance,
                (inRightBrow ? -1.0 : 1.0) * rightBrowDistance);
            // Fade across the actual brow edge, reaching slightly into sparse hairs.
            // There is no fully empty band surrounding a coarse brow centreline.
            mask *= smoothstep(-0.002, 0.0015, signedBrowDistance);
            float nostrilDistance = min(length((p - uNostrils.xy) * vec2(1.0, 1.5)),
                length((p - uNostrils.zw) * vec2(1.0, 1.5)));
            mask *= smoothstep(0.0015, 0.0035, nostrilDistance);
            if (mask < 0.0001) discard;

            vec2 uv = gl_FragCoord.xy / uViewport;
            vec3 camera = linearColor(texture2D(uCamera, uv).rgb);
            if (vSkin.w > 0.0) {
                // Only the extrapolated forehead strip needs this conservative colour
                // guard. It is not a hair segmentation model or a measured hairline.
                vec3 skinReference = linearColor(texture2D(uSoft, uReferences[2].xy).rgb);
                float referenceLight = max(luminance(skinReference), 0.015);
                float cameraLight = max(luminance(camera), 0.005);
                float ratio = cameraLight / referenceLight;
                float chromaDifference = length(camera / cameraLight - skinReference / referenceLight);
                float skinLike = smoothstep(0.25, 0.65, ratio) * (1.0 - smoothstep(1.8, 3.0, ratio)) *
                    (1.0 - smoothstep(0.25, 0.75, chromaDifference));
                mask *= mix(1.0, skinLike, smoothstep(0.0, 0.65, vSkin.w));
            }
            vec4 filtered = texture2D(uRetouchMap, uv);
            float smoothing = mask * clamp(uRetouch, 0.0, 1.0) * filtered.a;
            camera *= mix(1.0, skinSmoothingGain(camera, filtered), smoothing);
            vec3 soft = linearColor(texture2D(uSoft, uv).rgb);
            float cameraY = luminance(camera);
            float softY = max(luminance(soft), 0.002);
            float referenceY = 0.0; float referenceWeight = 0.0;
            for (int i = 0; i < 4; i++) {
                vec3 r = uReferences[i];
                float light = luminance(linearColor(texture2D(uSoft, r.xy).rgb));
                float weight = r.z * smoothstep(0.015, 0.07, light) * (1.0 - smoothstep(0.75, 0.98, light));
                referenceY += light * weight; referenceWeight += weight;
            }
            referenceY = referenceWeight > 0.01 ? referenceY / referenceWeight : softY;
            vec3 pigment = linearColor(uColor);
            float pigmentY = max(luminance(pigment), 0.002);
            // Bounded shade/lightness change preserves the captured large-scale face shading.
            float shadeGain = clamp(pigmentY / max(referenceY, 0.02), 0.65, 1.45);
            vec3 diffuse = pigment / pigmentY * softY * shadeGain;
            float coverage = clamp(uCoverage, 0.0, 1.0);
            // Retain pore luminance at all coverages; attenuate fine chromatic blemishes gradually.
            float detailY = clamp(cameraY / softY, 0.3, 3.0);
            vec3 chromaDetail = camera / max(soft * detailY, vec3(0.002));
            vec3 material = diffuse * detailY * mix(vec3(1.0), clamp(chromaDetail, 0.3, 3.0),
                1.0 - 0.85 * coverage);
            float highlight = smoothstep(0.5, 0.9, cameraY) *
                smoothstep(0.02, 0.12, cameraY - softY);
            float amount = mask * coverage * 0.90 * (1.0 - 0.85 * highlight);
            // Shadow/near-black guard avoids amplified chromatic noise.
            amount *= smoothstep(0.003, 0.025, cameraY);
            gl_FragColor = vec4(srgbColor(clamp(mix(camera, material, amount), 0.0, 1.0)), smoothing);
        }
    """

    /** Keep accepted blush lighting/coverage intact; change only its underlying diffuse colour. */
    fun blushOverFoundation(): String = BlushMaterial.FRAGMENT_SHADER
        .replace("uniform samplerExternalOES uCamera;", "uniform samplerExternalOES uCamera;\n        uniform sampler2D uFoundation;")
        .replace("vec3 base = toLinear(camera);",
            "vec3 base = toLinear(texture2D(uFoundation, gl_FragCoord.xy / uViewport).rgb);")
}
