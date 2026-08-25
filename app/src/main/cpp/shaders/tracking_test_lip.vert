#version 450

layout(location = 0) in vec2 predictorDisplayPosition;
layout(location = 1) in vec2 previousDisplayUv;
layout(location = 2) in float coverage;
layout(location = 3) in float faceNdcDepth;

layout(std430, set = 0, binding = 0) readonly buffer TemporalFitResult {
    vec4 values[4];
} temporalFit;

layout(push_constant) uniform TrackingLipState {
    // screenX = scaleX * displayX + offsetX
    // screenY = scaleY * displayY + offsetY. The positive-height Vulkan viewport used by this
    // renderer maps UV y=0 to the top of the framebuffer.
    vec4 displayToScreen;
    // x = clip-space lip depth bias, y/z = sampled NDC depth range.
    vec4 depthParameters;
    ivec4 flags;
    vec4 flowRegion;
} state;

layout(location = 0) out float fragmentCoverage;
layout(location = 1) out float sampledDepthDebug;
layout(location = 2) flat out int sampledDepthDebugEnabled;
layout(location = 3) out vec2 fragmentDisplayPosition;

void main() {
    vec2 displayPosition = predictorDisplayPosition;
    if (state.flags.x != 0) {
        vec4 globalFit = temporalFit.values[0];
        vec4 globalQuality = temporalFit.values[1];
        vec4 leftFlow = temporalFit.values[2];
        vec4 rightFlow = temporalFit.values[3];
        float mouthWidth = max(state.flowRegion.y - state.flowRegion.x, 1e-5);
        float mouthCenterX = (state.flowRegion.x + state.flowRegion.y) * 0.5;
        float mouthCenterY = (state.flowRegion.z + state.flowRegion.w) * 0.5;
        vec2 leftCorner = vec2(state.flowRegion.x, mouthCenterY);
        vec2 rightCorner = vec2(state.flowRegion.y, mouthCenterY);
        vec2 globalLeftTarget = vec2(
            globalFit.x * leftCorner.x - globalFit.y * leftCorner.y + globalFit.z,
            globalFit.y * leftCorner.x + globalFit.x * leftCorner.y + globalFit.w
        );
        vec2 globalRightTarget = vec2(
            globalFit.x * rightCorner.x - globalFit.y * rightCorner.y + globalFit.z,
            globalFit.y * rightCorner.x + globalFit.x * rightCorner.y + globalFit.w
        );
        vec2 leftLocalMotion = leftFlow.xy - (globalLeftTarget - leftCorner);
        vec2 rightLocalMotion = rightFlow.xy - (globalRightTarget - rightCorner);
        float globalReliability = globalQuality.w >= 0.5
            ? smoothstep(0.42, 0.65, globalQuality.x)
            : 0.0;
        float leftReliability = leftFlow.w >= 0.5
            ? globalReliability * smoothstep(0.42, 0.68, leftFlow.z)
            : 0.0;
        float rightReliability = rightFlow.w >= 0.5
            ? globalReliability * smoothstep(0.42, 0.68, rightFlow.z)
            : 0.0;
        float leftMagnitude = length(leftLocalMotion);
        float rightMagnitude = length(rightLocalMotion);
        float leftGate = smoothstep(0.0035, 0.0070, leftMagnitude) *
            (1.0 - smoothstep(0.020, 0.035, leftMagnitude));
        float rightGate = smoothstep(0.0035, 0.0070, rightMagnitude) *
            (1.0 - smoothstep(0.020, 0.035, rightMagnitude));
        float leftInfluence = 1.0 - smoothstep(
            state.flowRegion.x + mouthWidth * 0.08,
            mouthCenterX,
            previousDisplayUv.x
        );
        float rightInfluence = smoothstep(
            mouthCenterX,
            state.flowRegion.y - mouthWidth * 0.08,
            previousDisplayUv.x
        );
        vec2 localCorrection =
            leftLocalMotion * leftReliability * leftGate * leftInfluence +
            rightLocalMotion * rightReliability * rightGate * rightInfluence;
        displayPosition += localCorrection * 0.85;
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
