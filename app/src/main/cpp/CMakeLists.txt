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
set(retained_camera_fragment_spirv "${generated_shader_directory}/retained_camera.frag.spv")
set(camera_shader_header "${generated_shader_directory}/CameraShaders.generated.h")
set(tracking_test_lip_vertex_spirv "${generated_shader_directory}/tracking_test_lip.vert.spv")
set(tracking_test_lip_fragment_spirv "${generated_shader_directory}/tracking_test_lip.frag.spv")
set(face_occluder_vertex_spirv "${generated_shader_directory}/face_occluder.vert.spv")
set(face_occluder_fragment_spirv "${generated_shader_directory}/face_occluder.frag.spv")
set(temporal_luma_spirv "${generated_shader_directory}/temporal_luma.comp.spv")
set(temporal_downsample_spirv "${generated_shader_directory}/temporal_downsample.comp.spv")
set(temporal_flow_spirv "${generated_shader_directory}/temporal_flow.comp.spv")
set(temporal_fit_spirv "${generated_shader_directory}/temporal_fit.comp.spv")

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
    OUTPUT "${retained_camera_fragment_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=fragment
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/retained_camera.frag"
        -o "${retained_camera_fragment_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/retained_camera.frag"
    VERBATIM
)
add_custom_command(
    OUTPUT "${tracking_test_lip_vertex_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=vertex
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/tracking_test_lip.vert"
        -o "${tracking_test_lip_vertex_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/tracking_test_lip.vert"
    VERBATIM
)
add_custom_command(
    OUTPUT "${tracking_test_lip_fragment_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=fragment
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/tracking_test_lip.frag"
        -o "${tracking_test_lip_fragment_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/tracking_test_lip.frag"
    VERBATIM
)
add_custom_command(
    OUTPUT "${face_occluder_vertex_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=vertex
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/face_occluder.vert"
        -o "${face_occluder_vertex_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/face_occluder.vert"
    VERBATIM
)
add_custom_command(
    OUTPUT "${face_occluder_fragment_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=fragment
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/face_occluder.frag"
        -o "${face_occluder_fragment_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/face_occluder.frag"
    VERBATIM
)
add_custom_command(
    OUTPUT "${temporal_luma_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=compute
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/temporal_luma.comp"
        -o "${temporal_luma_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/temporal_luma.comp"
    VERBATIM
)
add_custom_command(
    OUTPUT "${temporal_downsample_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=compute
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/temporal_downsample.comp"
        -o "${temporal_downsample_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/temporal_downsample.comp"
    VERBATIM
)
add_custom_command(
    OUTPUT "${temporal_flow_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=compute
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/temporal_flow.comp"
        -o "${temporal_flow_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/temporal_flow.comp"
    VERBATIM
)
add_custom_command(
    OUTPUT "${temporal_fit_spirv}"
    COMMAND "${GLSLC_EXECUTABLE}" -O -fshader-stage=compute
        "${CMAKE_CURRENT_SOURCE_DIR}/shaders/temporal_fit.comp"
        -o "${temporal_fit_spirv}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/temporal_fit.comp"
    VERBATIM
)
add_custom_command(
    OUTPUT "${camera_shader_header}"
    COMMAND "${CMAKE_COMMAND}"
        -DVERTEX_SHADER=${camera_vertex_spirv}
        -DFRAGMENT_SHADER=${camera_fragment_spirv}
        -DRETAINED_CAMERA_FRAGMENT_SHADER=${retained_camera_fragment_spirv}
        -DTRACKING_TEST_LIP_VERTEX_SHADER=${tracking_test_lip_vertex_spirv}
        -DTRACKING_TEST_LIP_FRAGMENT_SHADER=${tracking_test_lip_fragment_spirv}
        -DFACE_OCCLUDER_VERTEX_SHADER=${face_occluder_vertex_spirv}
        -DFACE_OCCLUDER_FRAGMENT_SHADER=${face_occluder_fragment_spirv}
        -DTEMPORAL_LUMA_SHADER=${temporal_luma_spirv}
        -DTEMPORAL_DOWNSAMPLE_SHADER=${temporal_downsample_spirv}
        -DTEMPORAL_FLOW_SHADER=${temporal_flow_spirv}
        -DTEMPORAL_FIT_SHADER=${temporal_fit_spirv}
        -DOUTPUT_HEADER=${camera_shader_header}
        -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateShaderHeader.cmake"
    DEPENDS
        "${camera_vertex_spirv}"
        "${camera_fragment_spirv}"
        "${retained_camera_fragment_spirv}"
        "${tracking_test_lip_vertex_spirv}"
        "${tracking_test_lip_fragment_spirv}"
        "${face_occluder_vertex_spirv}"
        "${face_occluder_fragment_spirv}"
        "${temporal_luma_spirv}"
        "${temporal_downsample_spirv}"
        "${temporal_flow_spirv}"
        "${temporal_fit_spirv}"
        "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateShaderHeader.cmake"
    VERBATIM
)

add_library(
    armakeup_vulkan
    SHARED
    vulkan/VulkanBootstrap.cpp
    vulkan/VulkanDiagnosticRuntime.cpp
    image/Yuv420ToRgba.cpp
    "${camera_shader_header}"
)

# The converter is a tiny per-pixel debug bridge. Keep it optimized even in a debuggable APK so
# its scalar loop does not throttle the independent ARCore render session.
set_source_files_properties(image/Yuv420ToRgba.cpp PROPERTIES COMPILE_OPTIONS "-O3")

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
