#!/usr/bin/env sh
set -eu
bridge_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compiler=${1:-aarch64-w64-mingw32-clang}
output=${2:-"$bridge_dir/build/wow-session-bridge.exe"}
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM
mkdir -p "$(dirname -- "$output")"
# No PE timestamp, build ID, debug paths, random seed, or compiler-dependent
# link order: repeated builds with the same toolchain have identical bytes.
"$compiler" -std=c11 -Os -Wall -Wextra -Werror -ffile-prefix-map="$bridge_dir"=. \
    -c "$bridge_dir/bridge.c" -o "$build_dir/bridge.o"
"$compiler" -std=c11 -Os -Wall -Wextra -Werror -ffile-prefix-map="$bridge_dir"=. \
    -c "$bridge_dir/protocol.c" -o "$build_dir/protocol.o"
"$compiler" -std=c11 -Os -Wall -Wextra -ffile-prefix-map="$bridge_dir"=. \
    -Dprintf=bridge_device_printf -Dputs=bridge_device_puts \
    -c "$bridge_dir/device_id.c" -o "$build_dir/device_id.o"
# -mwindows: a console program gets a console window in the Wine desktop, and it stays on the screen in front of
# the game while the game loads. The helper prints nothing, so it needs no console.
"$compiler" -municode -mwindows -static -s -Wl,--no-insert-timestamp -Wl,--build-id=none \
    "$build_dir/bridge.o" "$build_dir/protocol.o" "$build_dir/device_id.o" \
    -lws2_32 -lcrypt32 -ladvapi32 -liphlpapi -luser32 -o "$output"
printf 'Built %s\n' "$output"
