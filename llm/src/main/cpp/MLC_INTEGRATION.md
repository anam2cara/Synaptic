MLC Native Integration - Instructions

Goal: build and integrate mlc-ai/mlc-llm native engine into Synaptic app as libmlcjni.so

Options (recommended order):
1) Prebuilt lib (fastest)
   - Place prebuilt libmlcjni.so for each ABI into llm/src/main/jniLibs/arm64-v8a/libmlcjni.so
   - Rebuild APK: ./gradlew.bat :app:assembleDebug

2) Build mlc-llm as submodule for Android (recommended for reproducibility)
   - Submodule is at llm/src/main/cpp/mlc_src (already added).
   - The mlc-llm project needs a prepared Android build. Steps (high-level):
     a) Install Android NDK (r21+ recommended) and toolchain; ensure ANDROID_NDK env or Android Studio configured.
     b) From repo root, bootstrap mlc_src thirdparty per mlc-llm docs (tvm/tokenizers etc.). This may require building TVM and tokenizers for Android which is non-trivial.
     c) Create marker file to enable add_subdirectory: touch llm/src/main/cpp/mlc_src/ANDROID_BUILD
     d) Ensure mlc_src CMake exports a target named `mlc` or `mlc_runtime` or `mlc_engine`.
     e) Rebuild APK: ./gradlew.bat :app:assembleDebug

3) If building native MLC from source is too heavy, convert your MLC model to a GGUF quantized model compatible with llama.cpp and use existing llama backend.

Notes & Troubleshooting
- The Gradle native configure will refuse to configure mlc_src unless thirdparty projects are prepared; that's why a marker file is used to opt-in.
- If you prefer, a CI job can build libmlcjni.so for arm64 and upload the artifact; then simply place the .so into jniLibs and rebuild.

If you want, the next steps I can perform:
- Attempt to build mlc-llm for Android here (requires time and toolchain; I can try), or
- Prepare a reproducible script and CMake glue to build libmlcjni in CI and copy to jniLibs, or
- Accept a prebuilt .so you provide and place it into jniLibs then rebuild the APK.

Which next step prefer? (I can try to build here, but will take substantial time.)