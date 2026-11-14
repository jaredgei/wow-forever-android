#!/usr/bin/env sh
set -eu
bridge_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compiler=${1:-x86_64-w64-mingw32-gcc}
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
"$compiler" -municode -static -s -Wl,--no-insert-timestamp -Wl,--build-id=none \
    "$build_dir/bridge.o" "$build_dir/protocol.o" "$build_dir/device_id.o" \
    -lws2_32 -lcrypt32 -ladvapi32 -liphlpapi -o "$output"
printf 'Built %s\n' "$output"
