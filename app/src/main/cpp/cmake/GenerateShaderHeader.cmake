file(READ "${VERTEX_SHADER}" VERTEX_HEX HEX)
file(READ "${FRAGMENT_SHADER}" FRAGMENT_HEX HEX)

string(REGEX REPLACE "(..)" "0x\\1," VERTEX_BYTES "${VERTEX_HEX}")
string(REGEX REPLACE "(..)" "0x\\1," FRAGMENT_BYTES "${FRAGMENT_HEX}")

file(WRITE "${OUTPUT_HEADER}" "#pragma once\n")
file(APPEND "${OUTPUT_HEADER}" "#include <cstdint>\n\n")
file(APPEND "${OUTPUT_HEADER}" "namespace armakeup::shaders {\n")
file(APPEND "${OUTPUT_HEADER}" "alignas(4) inline constexpr std::uint8_t kCameraVertex[] = {${VERTEX_BYTES}};\n")
file(APPEND "${OUTPUT_HEADER}" "alignas(4) inline constexpr std::uint8_t kCameraFragment[] = {${FRAGMENT_BYTES}};\n")
file(APPEND "${OUTPUT_HEADER}" "}  // namespace armakeup::shaders\n")
