#!/usr/bin/env bash
set -euo pipefail
test_dir="$(cd "$(dirname "$0")" && pwd)"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror ${CFLAGS:-} \
    "$test_dir/dns_hostname_test.c" -pthread -ldl -o "$build_dir/dns_hostname_test"
"$build_dir/dns_hostname_test"
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -O2 -fPIC -shared \
    "$test_dir/../gamenative_dns_v4mapped.c" -pthread -ldl -o "$build_dir/libdns.so"
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror \
    "$test_dir/dns_hostname_probe.c" -o "$build_dir/dns_hostname_probe"
GAMENATIVE_HOSTNAME=DESKTOP-TEST123 LD_PRELOAD="$build_dir/libdns.so" \
    "$build_dir/dns_hostname_probe"
