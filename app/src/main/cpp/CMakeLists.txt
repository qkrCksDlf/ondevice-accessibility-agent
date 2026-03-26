cmake_minimum_required(VERSION 3.22.1)
project("chat_app")

# 1. native-lib.cpp 파일을 컴파일해서 라이브러리로 만듭니다.
add_library(
        native-lib
        SHARED
        native-lib.cpp)

# 2. 안드로이드 기본 로그(Logcat) 기능을 C++에서도 쓰기 위해 연결합니다.
find_library(
        log-lib
        log)

target_link_libraries(
        native-lib
        ${log-lib})