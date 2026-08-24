#version 450

layout(location = 0) in float fragmentFaceDepth;
layout(location = 0) out vec4 outputColor;

void main() {
    // The production pipeline disables color writes. The diagnostic pipeline enables blending
    // and uses the same shader to expose the rasterized face-depth field.
    float normalizedDepth = clamp((fragmentFaceDepth - 0.80) / 0.18, 0.0, 1.0);
    vec3 depthColor = mix(vec3(0.0, 0.85, 1.0), vec3(1.0, 0.15, 0.0), normalizedDepth);
    outputColor = vec4(depthColor, 0.58);
}
