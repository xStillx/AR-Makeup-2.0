#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace {

std::uint8_t clampToByte(const int value) {
    return static_cast<std::uint8_t>(std::clamp(value, 0, 255));
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_armakeup_arcore_NativeYuv420Converter_nativeConvert(
    JNIEnv* environment,
    jclass /* type */,
    jobject yBuffer,
    jint yRowStride,
    jint yPixelStride,
    jobject uBuffer,
    jint uRowStride,
    jint uPixelStride,
    jobject vBuffer,
    jint vRowStride,
    jint vPixelStride,
    jint width,
    jint height,
    jobject rgbaBuffer
) {
    if (width <= 0 || height <= 0 || yRowStride <= 0 || yPixelStride <= 0 ||
        uRowStride <= 0 || uPixelStride <= 0 || vRowStride <= 0 ||
        vPixelStride <= 0) {
        return JNI_FALSE;
    }

    const auto* y = static_cast<const std::uint8_t*>(
        environment->GetDirectBufferAddress(yBuffer)
    );
    const auto* u = static_cast<const std::uint8_t*>(
        environment->GetDirectBufferAddress(uBuffer)
    );
    const auto* v = static_cast<const std::uint8_t*>(
        environment->GetDirectBufferAddress(vBuffer)
    );
    auto* rgba = static_cast<std::uint8_t*>(
        environment->GetDirectBufferAddress(rgbaBuffer)
    );
    const jlong rgbaCapacity = environment->GetDirectBufferCapacity(rgbaBuffer);
    const jlong requiredRgbaCapacity =
        static_cast<jlong>(width) * static_cast<jlong>(height) * 4;
    if (y == nullptr || u == nullptr || v == nullptr || rgba == nullptr ||
        rgbaCapacity < requiredRgbaCapacity) {
        return JNI_FALSE;
    }

    for (int row = 0; row < height; ++row) {
        const std::size_t yRow = static_cast<std::size_t>(row) * yRowStride;
        const std::size_t uRow = static_cast<std::size_t>(row / 2) * uRowStride;
        const std::size_t vRow = static_cast<std::size_t>(row / 2) * vRowStride;
        for (int column = 0; column < width; ++column) {
            const int yValue = y[yRow + static_cast<std::size_t>(column) * yPixelStride];
            const int uValue = u[uRow + static_cast<std::size_t>(column / 2) * uPixelStride] - 128;
            const int vValue = v[vRow + static_cast<std::size_t>(column / 2) * vPixelStride] - 128;
            const int luminance = std::max(yValue - 16, 0) * 298;
            const std::size_t output =
                (static_cast<std::size_t>(row) * width + column) * 4;
            rgba[output] = clampToByte((luminance + 409 * vValue + 128) >> 8);
            rgba[output + 1] = clampToByte(
                (luminance - 100 * uValue - 208 * vValue + 128) >> 8
            );
            rgba[output + 2] = clampToByte((luminance + 516 * uValue + 128) >> 8);
            rgba[output + 3] = 255U;
        }
    }
    return JNI_TRUE;
}
