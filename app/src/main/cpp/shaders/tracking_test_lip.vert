#version 450

layout(location = 0) in vec2 predictorDisplayPosition;
layout(location = 1) in vec2 previousDisplayUv;
layout(location = 2) in float coverage;
layout(location = 3) in float faceNdcDepth;

layout(std430, set = 0, binding = 0) readonly buffer TemporalFitResult {
    vec4 values[2];
} temporalFit;

layout(push_constant) uniform TrackingLipState {
    // screenX = scaleX * displayX + offsetX
    // screenY = scaleY * displayY + offsetY. The positive-height Vulkan viewport used by this
    // renderer maps UV y=0 to the top of the framebuffer.
    vec4 displayToScreen;
    // x = clip-space lip depth bias, y/z = sampled NDC depth range.
    vec4 depthParameters;
    ivec4 flags;
} state;

layout(location = 0) out float fragmentCoverage;
layout(location = 1) out float sampledDepthDebug;
layout(location = 2) flat out int sampledDepthDebugEnabled;
layout(location = 3) out vec2 fragmentDisplayPosition;

void main() {
    vec2 displayPosition = predictorDisplayPosition;
    if (state.flags.x != 0 && temporalFit.values[1].w >= 0.5) {
        vec4 fit = temporalFit.values[0];
        float confidence = temporalFit.values[1].x;
        float scale = length(fit.xy);
        float rotation = abs(atan(fit.y, fit.x));
        float translation = length(fit.zw);
        bool safe = confidence >= 0.48 &&
            scale >= 0.96 && scale <= 1.04 &&
            rotation <= 0.065 && translation <= 0.065;
        if (safe) {
            vec2 warpedDisplayUv = vec2(
                fit.x * previousDisplayUv.x - fit.y * previousDisplayUv.y + fit.z,
                fit.y * previousDisplayUv.x + fit.x * previousDisplayUv.y + fit.w
            );
            vec2 flowDisplayPosition = vec2(
                state.displayToScreen.x * warpedDisplayUv.x + state.displayToScreen.z,
                state.displayToScreen.y * warpedDisplayUv.y + state.displayToScreen.w
            );
            float motion = translation + rotation * 0.25 + abs(scale - 1.0) * 0.5;
            float motionWeight = smoothstep(0.003, 0.012, motion);
            float confidenceWeight = smoothstep(0.48, 0.70, confidence);
            float disagreement = length(flowDisplayPosition - predictorDisplayPosition);
            float safetyWeight = 1.0 - smoothstep(0.045, 0.075, disagreement);
            float weight = 0.90 * motionWeight * confidenceWeight * safetyWeight;
            displayPosition = mix(predictorDisplayPosition, flowDisplayPosition, weight);
        }
    }
    // Vulkan's positive-height viewport maps NDC -1 to the top of the framebuffer.
    gl_Position = vec4(
        displayPosition.x * 2.0 - 1.0,
        displayPosition.y * 2.0 - 1.0,
        clamp(faceNdcDepth * 0.5 + 0.5 + state.depthParameters.x, 0.0, 1.0),
        1.0
    );
    fragmentCoverage = coverage;
    float depthRange = state.depthParameters.z - state.depthParameters.y;
    sampledDepthDebug = depthRange > 1e-7
        ? clamp((faceNdcDepth - state.depthParameters.y) / depthRange, 0.0, 1.0)
        : 0.5;
    sampledDepthDebugEnabled = state.flags.y;
    fragmentDisplayPosition = displayPosition;
}
