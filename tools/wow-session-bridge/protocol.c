#include "protocol.h"
#include <string.h>

static int hex_digit(unsigned char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

int bridge_parse_config(const unsigned char *bytes, size_t length,
                        unsigned short *port, unsigned char nonce[BRIDGE_NONCE_SIZE]) {
    size_t i = 0;
    unsigned int parsed_port = 0;
    unsigned char parsed_nonce[BRIDGE_NONCE_SIZE];
    if (!bytes || !port || !nonce || length < 67 || length > 71) return 0;
    while (i < length && bytes[i] != '\n') {
        if (i == 5 || bytes[i] < '0' || bytes[i] > '9') return 0;
        parsed_port = parsed_port * 10 + (unsigned int)(bytes[i++] - '0');
    }
    if (!i || parsed_port == 0 || parsed_port > 65535 || i + 66 != length) return 0;
    i++;
    for (size_t n = 0; n < BRIDGE_NONCE_SIZE; n++) {
        int high = hex_digit(bytes[i++]), low = hex_digit(bytes[i++]);
        if (high < 0 || low < 0) return 0;
        parsed_nonce[n] = (unsigned char)((high << 4) | low);
    }
    if (bytes[i] != '\n') return 0;
    *port = (unsigned short)parsed_port;
    memcpy(nonce, parsed_nonce, sizeof(parsed_nonce));
    memset(parsed_nonce, 0, sizeof(parsed_nonce));
    return 1;
}

int bridge_printable_token(const unsigned char *bytes, size_t length) {
    if (!bytes || !length || length > BRIDGE_TOKEN_LIMIT) return 0;
    for (size_t i = 0; i < length; i++)
        if (bytes[i] < 32 || bytes[i] >= 127) return 0;
    return 1;
}

int bridge_equal_nonce(const unsigned char a[BRIDGE_NONCE_SIZE],
                       const unsigned char b[BRIDGE_NONCE_SIZE]) {
    unsigned int difference = 0;
    for (size_t i = 0; i < BRIDGE_NONCE_SIZE; i++) difference |= a[i] ^ b[i];
    return difference == 0;
}

void bridge_encode_length(uint32_t length, unsigned char out[4]) {
    out[0] = (unsigned char)(length >> 24);
    out[1] = (unsigned char)(length >> 16);
    out[2] = (unsigned char)(length >> 8);
    out[3] = (unsigned char)length;
}

uint32_t bridge_decode_length(const unsigned char in[4]) {
    return ((uint32_t)in[0] << 24) | ((uint32_t)in[1] << 16) |
           ((uint32_t)in[2] << 8) | (uint32_t)in[3];
}

static int append_character(wchar_t *command, size_t capacity, size_t *used, wchar_t c) {
    if (*used + 1 >= capacity) return 0;
    command[(*used)++] = c;
    command[*used] = 0;
    return 1;
}

/* Windows CommandLineToArgvW/CRT quoting: quote every argument, double trailing
 * backslashes and backslashes before quotes, and preserve empty arguments. */
int bridge_append_argument(wchar_t *command, size_t capacity, const wchar_t *argument) {
    size_t used = 0, slashes = 0;
    if (!command || !capacity || !argument) return 0;
    while (used < capacity && command[used]) used++;
    if (used == capacity) return 0;
    if (used && !append_character(command, capacity, &used, L' ')) return 0;
    if (!append_character(command, capacity, &used, L'"')) return 0;
    for (const wchar_t *p = argument;; p++) {
        if (*p == L'\\') { slashes++; continue; }
        size_t count = (*p == L'"' || *p == 0) ? slashes * 2 : slashes;
        for (size_t i = 0; i < count; i++)
            if (!append_character(command, capacity, &used, L'\\')) return 0;
        slashes = 0;
        if (*p == 0) break;
        if (*p == L'"' && !append_character(command, capacity, &used, L'\\')) return 0;
        if (!append_character(command, capacity, &used, *p)) return 0;
    }
    return append_character(command, capacity, &used, L'"');
}
