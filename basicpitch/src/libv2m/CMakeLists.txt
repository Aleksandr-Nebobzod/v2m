# Standalone build of the v2m library (shared object for JNI/Kotlin).
# The CLI (src_cli/CMakeLists.txt) compiles the same sources as a static lib.
cmake_minimum_required(VERSION 3.16)
project(v2m C CXX) # model.ort.c is C (compiled weights)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(V2M_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(BP ${V2M_ROOT}/basicpitch.cpp)

# Флаги релиза: MSVC не понимает -O3 (Windows собирает автосборка desktop).
if(MSVC)
    set(CMAKE_CXX_FLAGS_RELEASE "/O2 /DNDEBUG")
else()
    set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
endif()

# Платформенные части (этап 2 плана docs/260921_android_plan.md; Windows —
# portable-комплект, .github/workflows/desktop.yml):
#   Android — libonnxruntime.so из AAR (обязателен -DV2M_ORT_LIB), захват на
#             стороне Kotlin (AudioRecord), JNI-заголовки из sysroot NDK;
#   Linux   — libonnxruntime из системы (переопределяется -DV2M_ORT_LIB),
#             ALSA-захват, JNI-заголовки из JDK;
#   Windows — libonnxruntime из релиза ONNX Runtime (-DV2M_ORT_LIB — путь к
#             onnxruntime.lib, -DV2M_ORT_INCLUDE — его include), захвата нет:
#             ALSA отсутствует, WASAPI не реализован (решение А.М. 2026-09-24).
if(ANDROID)
    if(NOT V2M_ORT_LIB)
        message(FATAL_ERROR "V2M_ORT_LIB is required for the Android build (path to libonnxruntime.so from the ONNX Runtime AAR)")
    endif()
    set(V2M_PLATFORM_LIBS ${V2M_ORT_LIB})
elseif(WIN32)
    if(NOT V2M_ORT_LIB)
        message(FATAL_ERROR "V2M_ORT_LIB is required for the Windows build (path to onnxruntime.lib from the ONNX Runtime release)")
    endif()
    # libremidi дергает WinMM (MIDI-бэкенд Windows), ONNX Runtime — импорт-библиотеку.
    set(V2M_PLATFORM_LIBS ${V2M_ORT_LIB} winmm)
    set(JAVA_HOME_ENV $ENV{JAVA_HOME})
    if(NOT JAVA_HOME_ENV)
        message(FATAL_ERROR "JAVA_HOME is required for the Windows build (JNI headers: include, include/win32)")
    endif()
    include_directories(${JAVA_HOME_ENV}/include)
    include_directories(${JAVA_HOME_ENV}/include/win32)
    # Ресемплер Oboe включает <unistd.h>, которого в MSVC нет; шим добавляет
    # только этот каталог и только для Windows (msvc_compat/unistd.h).
    include_directories(${V2M_ROOT}/libv2m/msvc_compat)
else()
    # Умолчание — системный пакет Debian (libonnxruntime-dev); portable-сборка
    # передаёт -DV2M_ORT_LIB=<распакованный релиз ONNX Runtime>/lib/libonnxruntime.so.
    if(NOT V2M_ORT_LIB)
        set(V2M_ORT_LIB /usr/lib/x86_64-linux-gnu/libonnxruntime.so)
    endif()
    # ALSA capture (билд #43): JNI recorder no longer uses javax.sound.
    find_library(ASOUND_LIB asound REQUIRED)
    set(V2M_PLATFORM_LIBS ${V2M_ORT_LIB} ${ASOUND_LIB})
    # JNI headers (JDK; JBR from Android Studio would also work)
    set(JAVA_HOME_ENV $ENV{JAVA_HOME})
    if(JAVA_HOME_ENV)
        include_directories(${JAVA_HOME_ENV}/include)
        include_directories(${JAVA_HOME_ENV}/include/linux)
    else()
        include_directories(/usr/lib/jvm/java-21-openjdk-amd64/include)
        include_directories(/usr/lib/jvm/java-21-openjdk-amd64/include/linux)
    endif()
endif()

# Заголовки ONNX Runtime: по умолчанию из vendor basicpitch.cpp; сборка
# portable передаёт -DV2M_ORT_INCLUDE=<распакованный релиз>/include, чтобы
# версия заголовков совпадала с влинкованной библиотекой.
if(V2M_ORT_INCLUDE)
    include_directories(${V2M_ORT_INCLUDE})
else()
    include_directories(${BP}/vendor/onnxruntime/include)
endif()

include_directories(${V2M_ROOT}/libv2m)
include_directories(${BP}/src)
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

# v2m_capture.cpp — ALSA-захват: на Android запись идёт через AudioRecord
# (этап 5 плана), на Windows ALSA отсутствует. Тот же признак стоит на блоке
# NativeCapture в v2m_jni.cpp.
if(ANDROID OR WIN32)
    list(REMOVE_ITEM V2M_SOURCES "${V2M_ROOT}/libv2m/v2m_capture.cpp")
endif()

add_library(v2m SHARED ${V2M_SOURCES})
target_compile_definitions(v2m PRIVATE V2M_NO_MAIN=1 LIBREMIDI_HEADER_ONLY=1)
target_include_directories(v2m SYSTEM PRIVATE ${BP}/vendor/libremidi/include)
target_link_libraries(v2m ${V2M_PLATFORM_LIBS})

if(MSVC)
    # Многоконфигурационные генераторы MSVC кладут v2m.dll в подкаталог
    # конфигурации (build/Release/), а Gradle-задача packagePortable ждёт файл
    # в корне каталога сборки — как на Linux (:desktopApp:packagePortable).
    foreach(cfg DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        set_target_properties(v2m PROPERTIES RUNTIME_OUTPUT_DIRECTORY_${cfg} "${CMAKE_BINARY_DIR}")
    endforeach()
    # M_PI в CRT MSVC определён только при _USE_MATH_DEFINES (+ <math.h>), а его
    # использует ресемплер Oboe (MultiChannelResampler.cpp:148). Макрос задаём
    # строкой компиляции — так он определён до любого включения.
    target_compile_definitions(v2m PRIVATE _USE_MATH_DEFINES)
endif()

if(ANDROID)
    # Требование Google Play с 2025-11-01: страницы 16 КБ (Android 15+).
    # NDK 27 по умолчанию выравнивает сегменты на 4 КБ, поэтому просим явно.
    target_link_options(v2m PRIVATE "-Wl,-z,max-page-size=16384")
elseif(UNIX)
    # Portable-комплект: libonnxruntime.so лежит рядом с libv2m.so, $ORIGIN
    # избавляет пользователя от LD_LIBRARY_PATH (:desktopApp:packagePortable).
    # SKIP_BUILD_RPATH — чтобы в артефакт не попадал абсолютный путь к ONNX
    # Runtime машины сборки (CMake добавляет его сам при линковке по
    # абсолютному пути — так делает автосборка, .github/workflows/desktop.yml).
    set_target_properties(v2m PROPERTIES SKIP_BUILD_RPATH TRUE)
    target_link_options(v2m PRIVATE "-Wl,--enable-new-dtags" "-Wl,-rpath,\$ORIGIN")
endif()
