#!/usr/bin/env sh
set -eu
bridge_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
"${CC:-cc}" -std=c11 -O2 -Wall -Wextra -Werror \
    "$bridge_dir/protocol.c" "$bridge_dir/test_protocol.c" -o "$test_dir/test_protocol"
"$test_dir/test_protocol"
