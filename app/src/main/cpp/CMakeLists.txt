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

# Per-target juga (defense in depth — jika ada override di tempat lain)
target_link_options(llamajni PRIVATE "-Wl,-z,max-page-size=16384")

# Pre-built libllama.so (dari app/libs/arm64-v8a, direbuild via build_llama_16kb.ps1)
set(LLAMA_LIB "${CMAKE_SOURCE_DIR}/../../../libs/${ANDROID_ABI}/libllama.so")

find_library(log-lib log)
find_library(android-lib android)

if (EXISTS "${LLAMA_LIB}")
    add_library(llama SHARED IMPORTED)
    set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${LLAMA_LIB}")

    target_link_libraries(
        llamajni
        llama
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
