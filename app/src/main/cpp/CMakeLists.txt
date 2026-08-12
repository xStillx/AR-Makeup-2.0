cmake_minimum_required(VERSION 3.22.1)

project(armakeup_vulkan LANGUAGES CXX)

find_program(
    GLSLC_EXECUTABLE
    NAMES glslc glslc.exe
    HINTS
        "${ANDROID_NDK}/shader-tools/windows-x86_64"
        "${ANDROID_NDK}/shader-tools/linux-x86_64"
        "${ANDROID_NDK}/shader-tools/darwin-x86_64"
    REQUIRED
)

set(generated_shader_directory "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(camera_vertex_spirv "${generated_shader_directory}/camera_frame.vert.spv")
set(camera_fragment_spirv "${generated_shader_directory}/camera_frame.frag.spv")
set(camera_shader_header "${generated_shader_directory}/CameraShaders.generated.h")

add_custom_command(
    OUTPUT "${camera_vertex_spirv}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${generated_shader_directory}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=vertex
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/camera_frame.vert"
        -o "${camera_vertex_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/camera_frame.vert"
    VERBATIM
)
add_custom_command(
    OUTPUT "${camera_fragment_spirv}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${generated_shader_directory}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=fragment
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/camera_frame.frag"
        -o "${camera_fragment_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/camera_frame.frag"
    VERBATIM
)
add_custom_command(
    OUTPUT "${camera_shader_header}"
    COMMAND "${CMAKE_COMMAND}"
        -DVERTEX_SHADER=${camera_vertex_spirv}
        -DFRAGMENT_SHADER=${camera_fragment_spirv}
        -DOUTPUT_HEADER=${camera_shader_header}
        -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateShaderHeader.cmake"
    DEPENDS
        "${camera_vertex_spirv}"
        "${camera_fragment_spirv}"
        "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateShaderHeader.cmake"
    VERBATIM
)

add_library(
    armakeup_vulkan
    SHARED
    vulkan/VulkanBootstrap.cpp
    vulkan/VulkanDiagnosticRuntime.cpp
    "${camera_shader_header}"
)

find_library(android_log_library log REQUIRED)
find_library(android_native_library android REQUIRED)
find_library(dynamic_loader_library dl REQUIRED)

target_compile_features(armakeup_vulkan PRIVATE cxx_std_20)
target_include_directories(armakeup_vulkan PRIVATE "${generated_shader_directory}")
target_compile_options(
    armakeup_vulkan
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
    -fvisibility=hidden
)
target_link_libraries(
    armakeup_vulkan
    PRIVATE
    ${android_log_library}
    ${android_native_library}
    ${dynamic_loader_library}
)
