#!/bin/bash
# Build script for libdave-jvm Android native libraries

set -e

ANDROID_NDK=/opt/android-ndk
CMAKE=/usr/local/bin/cmake

# Android API level
ANDROID_API=21

# Architectures to build
ARCHS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")

# Base directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVES_SRC="${SCRIPT_DIR}/../libdave-jvm-master/natives"
OUTPUT_DIR="${SCRIPT_DIR}/natives-android/src/main/resources"

echo "Building libdave-jvm for Android..."
echo "NDK: ${ANDROID_NDK}"
echo "Source: ${NATIVES_SRC}"
echo "Output: ${OUTPUT_DIR}"

# Check if source exists
if [ ! -d "${NATIVES_SRC}" ]; then
    echo "ERROR: Natives source directory not found: ${NATIVES_SRC}"
    exit 1
fi

# Create output directories
for ARCH in "${ARCHS[@]}"; do
    mkdir -p "${OUTPUT_DIR}/${ARCH}"
done

# Build for each architecture
for ARCH in "${ARCHS[@]}"; do
    echo ""
    echo "========================================"
    echo "Building for ${ARCH}..."
    echo "========================================"
    
    BUILD_DIR="${NATIVES_SRC}/cmake-build-android-${ARCH}"
    
    # Determine Android ABI and toolchain settings
    case "${ARCH}" in
        arm64-v8a)
            ANDROID_ABI="arm64-v8a"
            CMAKE_SYSTEM_PROCESSOR="aarch64"
            ;;
        armeabi-v7a)
            ANDROID_ABI="armeabi-v7a"
            CMAKE_SYSTEM_PROCESSOR="armv7-a"
            ;;
        x86)
            ANDROID_ABI="x86"
            CMAKE_SYSTEM_PROCESSOR="i686"
            ;;
        x86_64)
            ANDROID_ABI="x86_64"
            CMAKE_SYSTEM_PROCESSOR="x86_64"
            ;;
    esac
    
    # Clean and create build directory
    rm -rf "${BUILD_DIR}"
    mkdir -p "${BUILD_DIR}"
    
    # Configure with CMake
    echo "Configuring ${ARCH}..."
    ${CMAKE} -S "${NATIVES_SRC}" \
          -B "${BUILD_DIR}" \
          -G "Ninja" \
          -DCMAKE_BUILD_TYPE=Release \
          -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake" \
          -DANDROID_ABI="${ANDROID_ABI}" \
          -DANDROID_PLATFORM=android-${ANDROID_API} \
          -DANDROID_STL=c++_static \
          -DANDROID_ARM_MODE=arm \
          -DANDROID_ARM_NEON=ON \
          -DREQUIRE_BORINGSSL=ON \
          -DDISABLE_PQ=ON \
          -DDISABLE_GREASE=ON \
          -DTESTING=OFF \
          -DBUILD_TESTING=OFF \
          -DCMAKE_FIND_PACKAGE_PREFER_CONFIG=ON
    
    # Build
    echo "Building ${ARCH}..."
    ${CMAKE} --build "${BUILD_DIR}" --config Release --parallel $(nproc)
    
    # Copy the built library
    if [ -f "${BUILD_DIR}/libdave-jvm.so" ]; then
        echo "Copying libdave-jvm.so to ${OUTPUT_DIR}/${ARCH}/"
        cp "${BUILD_DIR}/libdave-jvm.so" "${OUTPUT_DIR}/${ARCH}/"
        
        # Strip debug symbols to reduce size
        ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-debug "${OUTPUT_DIR}/${ARCH}/libdave-jvm.so"
    else
        echo "WARNING: libdave-jvm.so not found for ${ARCH}"
    fi
done

echo ""
echo "========================================"
echo "Build complete!"
echo "========================================"
ls -la "${OUTPUT_DIR}/"*/