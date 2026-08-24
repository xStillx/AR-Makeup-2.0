#version 450

layout(location = 0) in vec3 projectedFacePosition;

void main() {
    // Kotlin supplies normalized display x/y (top-left origin) and OpenGL-style NDC depth.
    // This renderer uses a positive-height viewport, so NDC -1 maps to the framebuffer top.
    gl_Position = vec4(
        projectedFacePosition.x * 2.0 - 1.0,
        projectedFacePosition.y * 2.0 - 1.0,
        clamp(projectedFacePosition.z * 0.5 + 0.5, 0.0, 1.0),
        1.0
    );
}
