cmake_minimum_required(VERSION 3.22.1)
project("synaptic_llm")

# ── 16 KB page-size alignment (wajib Android 15 / Android 16) ─────────────────
# Global: applies to SEMUA shared library yang dikompilasi oleh project CMake ini
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# JNI bridge
add_library(
    llamajni
    SHARED
    llama_jni.cpp
)

# MLC JNI bridge (prebuilt or stub)
add_library(
    mlcjni
    SHARED
    mlc_jni_stub.cpp
)

target_include_directories(mlcjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_options(mlcjni PRIVATE "-Wl,-z,max-page-size=16384")


# Wajib agar compiler menemukan llama.h, ggml.h, dll
target_include_directories(llamajni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Per-target juga (defense in depth — jika ada override di tempat lain)
target_link_options(llamajni PRIVATE "-Wl,-z,max-page-size=16384")

# Pre-built libraries
set(LLAMA_LIB "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so")
set(GGML_LIB "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so")
set(GGML_BASE_LIB "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-base.so")
set(GGML_CPU_LIB "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-cpu.so")
set(GGML_VULKAN_LIB "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-vulkan.so")

find_library(log-lib log)
find_library(android-lib android)

if (EXISTS "${LLAMA_LIB}")
    add_library(llama SHARED IMPORTED)
    set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${LLAMA_LIB}")

    add_library(ggml SHARED IMPORTED)
    set_target_properties(ggml PROPERTIES IMPORTED_LOCATION "${GGML_LIB}")

    add_library(ggml_base SHARED IMPORTED)
    set_target_properties(ggml_base PROPERTIES IMPORTED_LOCATION "${GGML_BASE_LIB}")

    add_library(ggml_cpu SHARED IMPORTED)
    set_target_properties(ggml_cpu PROPERTIES IMPORTED_LOCATION "${GGML_CPU_LIB}")

    add_library(ggml_vulkan SHARED IMPORTED)
    set_target_properties(ggml_vulkan PROPERTIES IMPORTED_LOCATION "${GGML_VULKAN_LIB}")

    target_link_libraries(
        llamajni
        llama
        ggml
        ggml_base
        ggml_cpu
        ggml_vulkan
        ${log-lib}
        ${android-lib}
    )
else()
    # Fallback: compile stub jika libllama.so belum ada
    target_sources(llamajni PRIVATE llama_stub.cpp)
    target_link_libraries(
        llamajni
        ${log-lib}
        ${android-lib}
    )
endif()

# Prefer building/linking a native MLC engine if source is available in mlc_src.
# If a submodule with source was added at llm/src/main/cpp/mlc_src and contains a CMakeLists.txt,
# include it and link the produced target (if present) to mlcjni.
if (EXISTS "${CMAKE_SOURCE_DIR}/mlc_src/CMakeLists.txt")
    message(STATUS "MLC source found at mlc_src; adding subdirectory")
    add_subdirectory("${CMAKE_SOURCE_DIR}/mlc_src" "${CMAKE_BINARY_DIR}/mlc_src_build")
    # Common possible exported target names: mlc, mlc_runtime, mlc_engine. Link if any exists.
    if (TARGET mlc)
        target_link_libraries(mlcjni mlc ${log-lib} ${android-lib})
    elseif (TARGET mlc_runtime)
        target_link_libraries(mlcjni mlc_runtime ${log-lib} ${android-lib})
    elseif (TARGET mlc_engine)
        target_link_libraries(mlcjni mlc_engine ${log-lib} ${android-lib})
    else()
        message(WARNING "MLC source added but no known target (mlc/mlc_runtime/mlc_engine) found. Will attempt to link prebuilt lib if present.")
    endif()
else()
    # If a prebuilt libmlcjni exists in jniLibs, prefer that. Otherwise build the stub mlcjni target we added above.
    set(MLC_LIB "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmlcjni.so")
    if (EXISTS "${MLC_LIB}")
        add_library(mlc SHARED IMPORTED)
        set_target_properties(mlc PROPERTIES IMPORTED_LOCATION "${MLC_LIB}")
        target_link_libraries(mlcjni mlc ${log-lib} ${android-lib})
    else()
        target_link_libraries(mlcjni ${log-lib} ${android-lib})
    endif()
endif()
