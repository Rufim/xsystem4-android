#!/bin/sh
#
# Сборка нативной части движка xsystem35 (System 3.x) и копирование .so в
# jniLibs единого приложения. Использует собственный android-CMake xsystem35
# (FetchContent SDL 2.32.10 + ttf + mixer + webp). libSDL2.so из этой сборки
# становится единым для обоих движков в APK (2.32.10 ABI-совместим с 2.30.9,
# которую использует xsystem4).

set -e

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo 'Set ANDROID_NDK_HOME to your Android NDK path.'
    exit 1
fi

ABI_NAMES=${ABI_NAMES:-arm64-v8a x86_64}
ANDROID_API_LEVEL=${ANDROID_API_LEVEL:-21}

ROOT=$(cd "$(dirname "$0")" && pwd)
JNI="$ROOT/xsystem35/android/app/jni"
DEST="$ROOT/project/app/src/main/jniLibs"
ASSETS="$ROOT/project/app/src/main/assets"

for abi in ${ABI_NAMES}; do
    B="$ROOT/build/xs35-${abi}"
    cmake -B "$B" -S "$JNI" -GNinja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_ANDROID_ARCH_ABI=${abi} \
        -DANDROID_ABI=${abi} \
        -DANDROID_PLATFORM=${ANDROID_API_LEVEL} \
        -DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"
    ninja -C "$B" xsystem35

    mkdir -p "$DEST/${abi}"
    cp "$B/xsystem35/src/libxsystem35.so"        "$DEST/${abi}/"
    cp "$B/_deps/sdl-build/libSDL2.so"           "$DEST/${abi}/"   # единый SDL2 2.32.10
    cp "$B/_deps/sdl_ttf-build/libSDL2_ttf.so"   "$DEST/${abi}/"
    cp "$B/_deps/sdl_mixer-build/libSDL2_mixer.so" "$DEST/${abi}/"
    # libxsystem35.so линкует webp динамически (+ его зависимость sharpyuv)
    cp "$B/_deps/libwebp-build/libwebp.so"       "$DEST/${abi}/"
    cp "$B/_deps/libwebp-build/libsharpyuv.so"   "$DEST/${abi}/"
done

# Шрифты xsystem35 в assets приложения (System 3.x рендерит текст через SDL_ttf)
mkdir -p "$ASSETS"
cp "$ROOT/xsystem35/fonts/MTLc3m.ttf" "$ASSETS/" 2>/dev/null || true
cp "$ROOT/xsystem35/fonts/mincho.ttf" "$ASSETS/" 2>/dev/null || true

echo "xsystem35: готово, .so в $DEST"
