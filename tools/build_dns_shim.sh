#!/usr/bin/env bash
# Rebuild the preloaded library packaged by Gradle (the shim is not a CMake target).
# Usage: tools/build_dns_shim.sh /path/to/android-ndk-r23c
set -euo pipefail
ndk_root="${1:?Pass the path to Android NDK r23c}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
case "$(uname -s)" in
    Linux) host_tag=linux-x86_64 ;;
    Darwin) host_tag=darwin-x86_64 ;;
    *) echo "Use the Linux or macOS NDK host toolchain" >&2; exit 1 ;;
esac
toolchain="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin"
output="$repo_root/app/src/main/jniLibs/arm64-v8a/libgamenative_dns_v4mapped.so"
temporary="$(mktemp)"
trap 'rm -f "$temporary"' EXIT
"$toolchain/aarch64-linux-android26-clang" -O2 -fPIC -shared \
    -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 \
    -Wl,--build-id=none -Wl,--exclude-libs,ALL \
    "$repo_root/app/src/main/cpp/gamenative_dns_v4mapped.c" \
    -ldl -o "$temporary"
"$toolchain/llvm-strip" --strip-unneeded "$temporary"
chmod 644 "$temporary"
mv "$temporary" "$output"
echo "Rebuilt $output"
