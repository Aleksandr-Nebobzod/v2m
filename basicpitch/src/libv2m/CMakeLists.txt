# Standalone build of the v2m library (shared object for JNI/Kotlin).
# The CLI (src_cli/CMakeLists.txt) compiles the same sources as a static lib.
cmake_minimum_required(VERSION 3.16)
project(v2m C CXX) # model.ort.c is C (compiled weights)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(V2M_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(BP ${V2M_ROOT}/basicpitch.cpp)

set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(ONNX_RUNTIME_LIB /usr/lib/x86_64-linux-gnu/libonnxruntime.so)

include_directories(${V2M_ROOT}/libv2m)
include_directories(${BP}/src)
# JNI headers (JDK; JBR from Android Studio would also work)
set(JAVA_HOME_ENV $ENV{JAVA_HOME})
if(JAVA_HOME_ENV)
    include_directories(${JAVA_HOME_ENV}/include)
    include_directories(${JAVA_HOME_ENV}/include/linux)
else()
    include_directories(/usr/lib/jvm/java-21-openjdk-amd64/include)
    include_directories(/usr/lib/jvm/java-21-openjdk-amd64/include/linux)
endif()
include_directories(${BP}/vendor/onnxruntime/include)
include_directories(${BP}/ort-model/model)
include_directories(${BP}/vendor/eigen)
include_directories(${BP}/vendor/oboe-resampler)
include_directories(${BP}/vendor/libremidi/include)
include_directories(${BP}/vendor/libnyquist/include)

file(GLOB V2M_SOURCES
    "${V2M_ROOT}/libv2m/*.cpp"
    "${V2M_ROOT}/midi2musicxml/midi2musicxml.cpp"
    "${BP}/src/*.cpp"
    "${BP}/ort-model/model/model.ort.c"
    "${BP}/vendor/oboe-resampler/*.cpp")

add_library(v2m SHARED ${V2M_SOURCES})
target_compile_definitions(v2m PRIVATE V2M_NO_MAIN=1 LIBREMIDI_HEADER_ONLY=1)
target_include_directories(v2m SYSTEM PRIVATE ${BP}/vendor/libremidi/include)
target_link_libraries(v2m ${ONNX_RUNTIME_LIB})
