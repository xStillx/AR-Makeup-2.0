#version 450

layout(location = 0) in vec2 displayPosition;
layout(location = 1) in float coverage;

layout(location = 0) out float fragmentCoverage;

void main() {
    // Vulkan's positive-height viewport maps NDC -1 to the top of the framebuffer.
    gl_Position = vec4(
        displayPosition.x * 2.0 - 1.0,
        displayPosition.y * 2.0 - 1.0,
        0.0,
        1.0
    );
    fragmentCoverage = coverage;
}
