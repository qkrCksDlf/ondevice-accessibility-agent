# 호스트(Windows) 컴파일러 설정
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR AMD64)

# MSVC 컴파일러 절대 경로
set(CMAKE_C_COMPILER   "C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.50.35717/bin/Hostx64/x64/cl.exe")
set(CMAKE_CXX_COMPILER "C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.50.35717/bin/Hostx64/x64/cl.exe")

# Windows SDK 리소스 컴파일러 (rc.exe) 절대 경로
set(CMAKE_RC_COMPILER  "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64/rc.exe")
set(CMAKE_MT           "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64/mt.exe")

# Windows SDK include / lib 경로
include_directories(SYSTEM
    "C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0/ucrt"
    "C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0/um"
    "C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0/shared"
    "C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.50.35717/include"
)

link_directories(
    "C:/Program Files (x86)/Windows Kits/10/Lib/10.0.26100.0/ucrt/x64"
    "C:/Program Files (x86)/Windows Kits/10/Lib/10.0.26100.0/um/x64"
    "C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.50.35717/lib/x64"
)

# Android NDK 영향 차단
unset(CMAKE_TOOLCHAIN_FILE CACHE)
unset(ANDROID CACHE)
unset(ANDROID_ABI CACHE)
unset(ANDROID_PLATFORM CACHE)
unset(ANDROID_NDK CACHE)

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
