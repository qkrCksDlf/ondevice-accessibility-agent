cmake_minimum_required(VERSION 3.22.1)
project("chat_app")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# GPU 백엔드들 끄기
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

add_library(
        native-lib
        SHARED
        native-lib.cpp
)

find_library(
        log-lib
        log
)

target_include_directories(
        native-lib
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
)

target_link_libraries(
        native-lib
        whisper
        ${log-lib}
)