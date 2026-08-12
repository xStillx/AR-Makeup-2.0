cmake_minimum_required(VERSION 3.22.1)

project(armakeup_vulkan LANGUAGES CXX)

add_library(
    armakeup_vulkan
    SHARED
    vulkan/VulkanBootstrap.cpp
    vulkan/VulkanDiagnosticRuntime.cpp
)

find_library(android_log_library log REQUIRED)
find_library(android_native_library android REQUIRED)
find_library(dynamic_loader_library dl REQUIRED)

target_compile_features(armakeup_vulkan PRIVATE cxx_std_20)
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
