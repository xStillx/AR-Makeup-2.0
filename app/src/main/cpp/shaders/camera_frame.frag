#version 450

layout(set = 0, binding = 0) uniform sampler2D cameraTexture;

layout(push_constant) uniform CameraFrameTransform {
    mat4 uvTransform;
} frameState;

layout(location = 0) in vec2 textureUv;
layout(location = 0) out vec4 outputColor;

void main() {
    vec2 cameraUv = (frameState.uvTransform * vec4(textureUv, 0.0, 1.0)).xy;
    outputColor = texture(cameraTexture, cameraUv);
}
