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
