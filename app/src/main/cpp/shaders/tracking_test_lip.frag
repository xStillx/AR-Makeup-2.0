#version 450

layout(location = 0) in float fragmentCoverage;
layout(location = 1) in float sampledDepthDebug;
layout(location = 2) flat in int sampledDepthDebugEnabled;
layout(location = 3) in vec2 fragmentDisplayPosition;
layout(location = 0) out vec4 outputColor;

layout(std430, set = 0, binding = 1) readonly buffer DynamicLipContour {
    // xy = outer point, zw = corresponding inner point.
    vec4 pairedPoints[20];
    // x = enabled, y = opacity, z = feather width, w = point count.
    vec4 parameters;
} contour;

vec2 loopPoint(int index, bool outer) {
    vec4 paired = contour.pairedPoints[index];
    return outer ? paired.xy : paired.zw;
}

bool containsLoop(vec2 point, bool outer) {
    bool inside = false;
    vec2 previous = loopPoint(19, outer);
    for (int index = 0; index < 20; ++index) {
        vec2 current = loopPoint(index, outer);
        if ((current.y > point.y) != (previous.y > point.y)) {
            float intersectionX = (previous.x - current.x) *
                (point.y - current.y) / (previous.y - current.y) + current.x;
            if (point.x < intersectionX) {
                inside = !inside;
            }
        }
        previous = current;
    }
    return inside;
}

float distanceToSegment(vec2 point, vec2 first, vec2 second) {
    vec2 edge = second - first;
    float lengthSquared = dot(edge, edge);
    if (lengthSquared <= 1e-12) {
        return length(point - first);
    }
    float fraction = clamp(dot(point - first, edge) / lengthSquared, 0.0, 1.0);
    return length(point - (first + edge * fraction));
}

float distanceToLoop(vec2 point, bool outer) {
    float minimumDistance = 1e10;
    vec2 previous = loopPoint(19, outer);
    for (int index = 0; index < 20; ++index) {
        vec2 current = loopPoint(index, outer);
        minimumDistance = min(
            minimumDistance,
            distanceToSegment(point, previous, current)
        );
        previous = current;
    }
    return minimumDistance;
}

void main() {
    const vec3 trackingTestSrgb = vec3(1.0, 0.0, 0.83137255);
    float alpha = clamp(fragmentCoverage * 4.0, 0.0, 1.0);
    if (contour.parameters.x >= 0.5) {
        bool insideOuter = containsLoop(fragmentDisplayPosition, true);
        bool insideInner = containsLoop(fragmentDisplayPosition, false);
        if (!insideOuter || insideInner) {
            alpha = 0.0;
        } else {
            float edgeDistance = min(
                distanceToLoop(fragmentDisplayPosition, true),
                distanceToLoop(fragmentDisplayPosition, false)
            );
            alpha = smoothstep(0.0, contour.parameters.z, edgeDistance) *
                contour.parameters.y;
        }
    }
    vec3 depthColor = mix(vec3(0.0, 0.85, 1.0), vec3(1.0, 0.15, 0.0), sampledDepthDebug);
    outputColor = vec4(
        sampledDepthDebugEnabled != 0 ? depthColor : trackingTestSrgb,
        alpha
    );
}
