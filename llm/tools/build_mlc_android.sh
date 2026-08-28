#!/usr/bin/env bash
set -euo pipefail

# Reproducible script to build mlc-llm native engine for Android (arm64-v8a)
# Place this repo at workspace root and run from repo root.
# Before running: ensure Android NDK is installed and ANDROID_NDK environment variable points to it.

NDK=${ANDROID_NDK:-}
if [ -z "$NDK" ]; then
  echo "ANDROID_NDK not set. Install NDK and set ANDROID_NDK env var." >&2
  exit 2
fi

ABI=${ABI:-arm64-v8a}
API=${API:-26}
ROOT=$(pwd)
MLC_SRC=${ROOT}/llm/src/main/cpp/mlc_src
BUILD_DIR=${ROOT}/llm/build-mlc-android
JNILIB_DIR=${ROOT}/llm/src/main/jniLibs/${ABI}

mkdir -p "$BUILD_DIR" "$JNILIB_DIR"

echo "Configuring mlc-llm for Android (ABI=$ABI, API=$API)"
cmake -S "$MLC_SRC" -B "$BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-${API}" \
  -DCMAKE_BUILD_TYPE=Release

echo "Building mlc-llm (this may take a long time)..."
cmake --build "$BUILD_DIR" -- -j$(nproc)

# Attempt to locate produced lib - common names: libmlcjni.so, libmlc.so, libmlc_runtime.so
LIB_PATHS=("$BUILD_DIR" "$BUILD_DIR/lib" "$BUILD_DIR/bin" "$BUILD_DIR/lib/${ABI}")
FOUND_LIB=""
for p in "${LIB_PATHS[@]}"; do
  if [ -f "$p/libmlcjni.so" ]; then FOUND_LIB="$p/libmlcjni.so"; break; fi
  if [ -f "$p/libmlc.so" ]; then FOUND_LIB="$p/libmlc.so"; break; fi
  if [ -f "$p/libmlc_runtime.so" ]; then FOUND_LIB="$p/libmlc_runtime.so"; break; fi
  if [ -f "$p/libmlc_engine.so" ]; then FOUND_LIB="$p/libmlc_engine.so"; break; fi
done

if [ -z "$FOUND_LIB" ]; then
  echo "Could not find built library automatically. Please locate the produced .so in $BUILD_DIR and copy it to $JNILIB_DIR/libmlcjni.so" >&2
  exit 3
fi

cp "$FOUND_LIB" "$JNILIB_DIR/libmlcjni.so"
echo "Copied $FOUND_LIB to $JNILIB_DIR/libmlcjni.so"

echo "Done. Now run ./gradlew :app:assembleDebug to build the APK with native MLC library."