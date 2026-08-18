#version 450

layout(location = 0) in float fragmentCoverage;
layout(location = 0) out vec4 outputColor;

void main() {
    const vec3 trackingTestSrgb = vec3(1.0, 0.0, 0.83137255);
    float alpha = clamp(fragmentCoverage * 4.0, 0.0, 1.0);
    outputColor = vec4(trackingTestSrgb, alpha);
}
