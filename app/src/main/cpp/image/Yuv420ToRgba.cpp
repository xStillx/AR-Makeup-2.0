#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

std::uint8_t clampToByte(const int value) {
    return static_cast<std::uint8_t>(std::clamp(value, 0, 255));
}

void writeRgba(
    std::uint8_t* rgba,
    const std::size_t output,
    const int yValue,
    const int uValue,
    const int vValue
) {
    const int luminance = std::max(yValue - 16, 0) * 298;
    rgba[output] = clampToByte((luminance + 409 * vValue + 128) >> 8);
    rgba[output + 1] = clampToByte(
        (luminance - 100 * uValue - 208 * vValue + 128) >> 8
    );
    rgba[output + 2] = clampToByte((luminance + 516 * uValue + 128) >> 8);
    rgba[output + 3] = 255U;
}

struct BilinearAxis {
    int lower;
    int upper;
    int fractionQ8;
};

BilinearAxis makeBilinearAxis(const std::int64_t coordinateQ16, const int size) {
    const std::int64_t maximumQ16 =
        static_cast<std::int64_t>(std::max(size - 1, 0)) << 16;
    const std::int64_t clampedQ16 = std::clamp<std::int64_t>(
        coordinateQ16,
        0,
        maximumQ16
    );
    const int lower = static_cast<int>(clampedQ16 >> 16);
    return {
        lower,
        std::min(lower + 1, size - 1),
        static_cast<int>((clampedQ16 >> 8) & 0xFF),
    };
}

int samplePlaneBilinearQ8(
    const std::uint8_t* plane,
    const int rowStride,
    const int pixelStride,
    const BilinearAxis& x,
    const BilinearAxis& y
) {
    const auto* upperRow =
        plane + static_cast<std::size_t>(y.lower) * rowStride;
    const auto* lowerRow =
        plane + static_cast<std::size_t>(y.upper) * rowStride;
    const std::size_t leftOffset = static_cast<std::size_t>(x.lower) * pixelStride;
    const std::size_t rightOffset = static_cast<std::size_t>(x.upper) * pixelStride;
    const int inverseX = 256 - x.fractionQ8;
    const int inverseY = 256 - y.fractionQ8;
    const int top =
        upperRow[leftOffset] * inverseX + upperRow[rightOffset] * x.fractionQ8;
    const int bottom =
        lowerRow[leftOffset] * inverseX + lowerRow[rightOffset] * x.fractionQ8;
    return (top * inverseY + bottom * y.fractionQ8 + 32768) >> 16;
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
    jint sourceWidth,
    jint sourceHeight,
    jint outputWidth,
    jint outputHeight,
    jobject rgbaBuffer
) {
    if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 ||
        outputHeight <= 0 || yRowStride <= 0 || yPixelStride <= 0 ||
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
        static_cast<jlong>(outputWidth) * static_cast<jlong>(outputHeight) * 4;
    if (y == nullptr || u == nullptr || v == nullptr || rgba == nullptr ||
        rgbaCapacity < requiredRgbaCapacity) {
        return JNI_FALSE;
    }

    if (sourceWidth == outputWidth && sourceHeight == outputHeight) {
        for (int row = 0; row < sourceHeight; ++row) {
            const std::size_t yRow = static_cast<std::size_t>(row) * yRowStride;
            const std::size_t uRow =
                static_cast<std::size_t>(row / 2) * uRowStride;
            const std::size_t vRow =
                static_cast<std::size_t>(row / 2) * vRowStride;
            for (int column = 0; column < sourceWidth; ++column) {
                const int yValue =
                    y[yRow + static_cast<std::size_t>(column) * yPixelStride];
                const int uValue =
                    u[uRow + static_cast<std::size_t>(column / 2) * uPixelStride] -
                    128;
                const int vValue =
                    v[vRow + static_cast<std::size_t>(column / 2) * vPixelStride] -
                    128;
                const std::size_t output =
                    (static_cast<std::size_t>(row) * outputWidth + column) * 4;
                writeRgba(rgba, output, yValue, uValue, vValue);
            }
        }
        return JNI_TRUE;
    }

    const int chromaWidth = (sourceWidth + 1) / 2;
    const int chromaHeight = (sourceHeight + 1) / 2;
    const std::int64_t sourceStepXQ16 =
        (static_cast<std::int64_t>(sourceWidth) << 16) / outputWidth;
    const std::int64_t sourceStepYQ16 =
        (static_cast<std::int64_t>(sourceHeight) << 16) / outputHeight;
    const std::int64_t sourceStartXQ16 = sourceStepXQ16 / 2 - (1 << 15);
    const std::int64_t sourceStartYQ16 = sourceStepYQ16 / 2 - (1 << 15);
    std::vector<BilinearAxis> lumaColumns(static_cast<std::size_t>(outputWidth));
    std::vector<BilinearAxis> chromaColumns(static_cast<std::size_t>(outputWidth));
    for (int column = 0; column < outputWidth; ++column) {
        const std::int64_t sourceXQ16 =
            sourceStartXQ16 + static_cast<std::int64_t>(column) * sourceStepXQ16;
        lumaColumns[static_cast<std::size_t>(column)] =
            makeBilinearAxis(sourceXQ16, sourceWidth);
        chromaColumns[static_cast<std::size_t>(column)] =
            makeBilinearAxis(sourceXQ16 / 2, chromaWidth);
    }
    for (int row = 0; row < outputHeight; ++row) {
        const std::int64_t sourceYQ16 =
            sourceStartYQ16 + static_cast<std::int64_t>(row) * sourceStepYQ16;
        const BilinearAxis lumaRow = makeBilinearAxis(sourceYQ16, sourceHeight);
        const BilinearAxis chromaRow =
            makeBilinearAxis(sourceYQ16 / 2, chromaHeight);
        for (int column = 0; column < outputWidth; ++column) {
            const auto& lumaColumn = lumaColumns[static_cast<std::size_t>(column)];
            const auto& chromaColumn =
                chromaColumns[static_cast<std::size_t>(column)];
            const int yValue = samplePlaneBilinearQ8(
                y, yRowStride, yPixelStride, lumaColumn, lumaRow
            );
            const int uValue = samplePlaneBilinearQ8(
                u, uRowStride, uPixelStride, chromaColumn, chromaRow
            ) - 128;
            const int vValue = samplePlaneBilinearQ8(
                v, vRowStride, vPixelStride, chromaColumn, chromaRow
            ) - 128;
            const std::size_t output =
                (static_cast<std::size_t>(row) * outputWidth + column) * 4;
            writeRgba(rgba, output, yValue, uValue, vValue);
        }
    }
    return JNI_TRUE;
}
