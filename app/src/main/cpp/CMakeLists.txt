cmake_minimum_required(VERSION 3.22.1)

project("helpagent")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on Android" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libs" FORCE)

# ========================================================
# Vulkan 백엔드 활성화
# ========================================================
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)

# ========================================================
# 🌟 b4500: 호스트(Windows) MSVC toolchain 파일 지정
# ========================================================
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
        "${CMAKE_CURRENT_SOURCE_DIR}/host-toolchain/host-toolchain.cmake"
        CACHE FILEPATH "" FORCE)

if(NOT EXISTS "${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}")
    message(FATAL_ERROR "host-toolchain.cmake 없음: ${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}")
endif()

message(STATUS "Host toolchain: ${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}")

# ========================================================
# Vulkan SDK 헤더
# ========================================================
if(DEFINED ENV{VULKAN_SDK})
    set(VULKAN_SDK_INCLUDE "$ENV{VULKAN_SDK}/Include")
else()
    set(VULKAN_SDK_INCLUDE "C:/VulkanSDK/1.4.341.1/Include")
endif()

if(NOT EXISTS "${VULKAN_SDK_INCLUDE}/vulkan/vulkan.hpp")
    message(FATAL_ERROR "vulkan.hpp 없음: ${VULKAN_SDK_INCLUDE}")
endif()

message(STATUS "Vulkan SDK: ${VULKAN_SDK_INCLUDE}")

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -I${VULKAN_SDK_INCLUDE}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -I${VULKAN_SDK_INCLUDE}")

# -----------------------------
# llama.cpp
# -----------------------------
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if(NOT EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp 없음: ${LLAMA_CPP_DIR}")
endif()

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_BINARY_DIR}/llama_cpp_build)

# -----------------------------
# native-lib
# -----------------------------
add_library(native-lib SHARED native-lib.cpp)

target_include_directories(native-lib PRIVATE
        ${LLAMA_CPP_DIR}
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
)

find_library(log-lib log)
find_library(vulkan-lib vulkan)

if(NOT vulkan-lib)
    message(FATAL_ERROR "Vulkan 없음")
endif()

target_link_libraries(native-lib
        ${log-lib}
        ${vulkan-lib}
        "-Wl,--whole-archive"
        llama
        ggml
        "-Wl,--no-whole-archive"
)

target_compile_options(native-lib PRIVATE -O2 -fexceptions -frtti)