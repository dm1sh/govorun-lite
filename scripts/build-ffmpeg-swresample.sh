#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FFMPEG_VERSION="7.1.1"
CACHE="$ROOT/.ffmpeg-src"
BUILD="$ROOT/ffmpeg-build"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-}}}"
if [[ -z "$NDK" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  NDK="$(find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
fi

if [[ -z "$NDK" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT is required" >&2
  exit 1
fi

if [[ ! -f "$CACHE/ffmpeg-$FFMPEG_VERSION/configure" ]]; then
  mkdir -p "$CACHE"
  curl --fail --location --retry 3 \
    "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" \
    -o "$CACHE/ffmpeg.tar.xz"
  tar -xJf "$CACHE/ffmpeg.tar.xz" -C "$CACHE"
fi

SRC="$CACHE/ffmpeg-$FFMPEG_VERSION"
rm -rf "$BUILD"
mkdir -p "$BUILD"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
export CC="$TOOLCHAIN/aarch64-linux-android33-clang"
export CXX="$TOOLCHAIN/aarch64-linux-android33-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export RANLIB="$TOOLCHAIN/llvm-ranlib"
export STRIP="$TOOLCHAIN/llvm-strip"

cd "$SRC"
make distclean >/dev/null 2>&1 || true
./configure \
  --prefix="$BUILD" \
  --target-os=android \
  --arch=aarch64 \
  --enable-cross-compile \
  --sysroot="$SYSROOT" \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --disable-programs \
  --disable-doc \
  --disable-network \
  --disable-everything \
  --enable-avutil \
  --enable-swresample \
  --enable-pic \
  --disable-shared \
  --enable-static \
  --disable-gpl \
  --disable-nonfree \
  --disable-debug
make -j"$(nproc)" && make install
