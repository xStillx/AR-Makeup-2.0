#version 450

layout(location = 0) in float fragmentCoverage;
layout(location = 1) in float sampledDepthDebug;
layout(location = 2) flat in int sampledDepthDebugEnabled;
layout(location = 0) out vec4 outputColor;

void main() {
    const vec3 trackingTestSrgb = vec3(1.0, 0.0, 0.83137255);
    float alpha = clamp(fragmentCoverage * 4.0, 0.0, 1.0);
    vec3 depthColor = mix(vec3(0.0, 0.85, 1.0), vec3(1.0, 0.15, 0.0), sampledDepthDebug);
    outputColor = vec4(
        sampledDepthDebugEnabled != 0 ? depthColor : trackingTestSrgb,
        alpha
    );
}
