# GIA Studio native audio core.
# Built only when Gradle is invoked with -PwithNative (see app/build.gradle.kts),
# so the pure-Kotlin build keeps working without an NDK.

cmake_minimum_required(VERSION 3.22.1)
project(giastudio_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe — Google's low-latency C++ audio library for Android.
# Pinned to a released tag so builds are reproducible.
include(FetchContent)
set(FETCHCONTENT_QUIET OFF)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.9.3
)
FetchContent_MakeAvailable(oboe)

add_library(giastudio SHARED
    jni_bridge.cpp
    engine/NativeEngine.cpp
    plugins/TestSynth.cpp
)

target_include_directories(giastudio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(giastudio oboe log android)