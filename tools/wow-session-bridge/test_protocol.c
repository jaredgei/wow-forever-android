#include "protocol.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_config(void) {
    const unsigned char valid[] =
        "54321\n000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\n";
    unsigned short port = 0;
    unsigned char nonce[BRIDGE_NONCE_SIZE] = {0};
    assert(bridge_parse_config(valid, sizeof(valid) - 1, &port, nonce));
    assert(port == 54321);
    for (size_t i = 0; i < sizeof(nonce); i++) assert(nonce[i] == i);
    for (size_t length = 0; length < sizeof(valid) - 1; length++)
        assert(!bridge_parse_config(valid, length, &port, nonce));
    assert(!bridge_parse_config(valid, sizeof(valid), &port, nonce));
    unsigned char malformed[sizeof(valid)];
    memcpy(malformed, valid, sizeof(valid));
    malformed[0] = 'x';
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    memcpy(malformed, valid, sizeof(valid));
    memcpy(malformed, "65536", 5);
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    memcpy(malformed, "00000", 5);
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    memcpy(malformed, valid, sizeof(valid));
    malformed[10] = 'g';
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    memcpy(malformed, valid, sizeof(valid));
    malformed[sizeof(valid) - 2] = '\r';
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    /* A malformed input must not partially replace the caller's output. */
    port = 9000;
    memset(nonce, 0xa5, sizeof(nonce));
    assert(!bridge_parse_config(malformed, sizeof(valid) - 1, &port, nonce));
    assert(port == 9000);
    for (size_t i = 0; i < sizeof(nonce); i++) assert(nonce[i] == 0xa5);
    assert(!bridge_parse_config(NULL, 71, &port, nonce));
}

static void test_frames_and_tokens(void) {
    unsigned char bytes[4];
    const uint32_t values[] = {0, 1, 255, 256, 65535, 65536, 0x12345678, UINT32_MAX};
    for (size_t i = 0; i < sizeof(values) / sizeof(values[0]); i++) {
        bridge_encode_length(values[i], bytes);
        assert(bridge_decode_length(bytes) == values[i]);
    }
    bridge_encode_length(0x12345678, bytes);
    assert(bytes[0] == 0x12 && bytes[1] == 0x34 && bytes[2] == 0x56 && bytes[3] == 0x78);
    unsigned char token[BRIDGE_TOKEN_LIMIT + 1];
    memset(token, 'x', sizeof(token));
    assert(!bridge_printable_token(token, 0));
    assert(bridge_printable_token(token, 1));
    assert(bridge_printable_token(token, BRIDGE_TOKEN_LIMIT));
    assert(!bridge_printable_token(token, sizeof(token)));
    for (unsigned int c = 0; c <= 255; c++) {
        token[0] = (unsigned char)c;
        assert(bridge_printable_token(token, 1) == (c >= 32 && c < 127));
    }
    unsigned char a[BRIDGE_NONCE_SIZE] = {0}, b[BRIDGE_NONCE_SIZE] = {0};
    assert(bridge_equal_nonce(a, b));
    for (size_t i = 0; i < sizeof(b); i++) {
        b[i] = 1;
        assert(!bridge_equal_nonce(a, b));
        b[i] = 0;
    }
}

static void test_arguments(void) {
    wchar_t command[256] = {0};
    assert(bridge_append_argument(command, 256, L"C:\\WoW Forever\\Wow-ARM64.exe"));
    assert(bridge_append_argument(command, 256, L"-launcherlogin"));
    assert(wcscmp(command, L"\"C:\\WoW Forever\\Wow-ARM64.exe\" \"-launcherlogin\"") == 0);
    command[0] = 0;
    assert(bridge_append_argument(command, 256, L""));
    assert(bridge_append_argument(command, 256, L"trailing\\"));
    assert(bridge_append_argument(command, 256, L"say\"hello"));
    assert(wcscmp(command, L"\"\" \"trailing\\\\\" \"say\\\"hello\"") == 0);
    command[0] = 0;
    assert(bridge_append_argument(command, 256, L"\\\""));
    assert(wcscmp(command, L"\"\\\\\\\"\"") == 0);
    wchar_t exact[5] = {0};
    assert(bridge_append_argument(exact, 5, L"ab"));
    wchar_t guarded[5] = {0, 0, 0, 0, L'X'};
    assert(!bridge_append_argument(guarded, 4, L"ab"));
    assert(guarded[4] == L'X');
    wchar_t unterminated[2] = {L'x', L'x'};
    assert(!bridge_append_argument(unterminated, 2, L"a"));
}

int main(void) {
    test_config();
    test_frames_and_tokens();
    test_arguments();
    puts("Session bridge protocol tests passed");
    return 0;
}
