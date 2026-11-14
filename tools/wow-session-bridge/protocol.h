#ifndef WOW_SESSION_PROTOCOL_H
#define WOW_SESSION_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>
#include <wchar.h>

#define BRIDGE_NONCE_SIZE 32
#define BRIDGE_TOKEN_LIMIT 2048
#define BRIDGE_DEVICE_LIMIT 4096
#define BRIDGE_COMMAND_LIMIT 32768

int bridge_parse_config(const unsigned char *bytes, size_t length,
                        unsigned short *port, unsigned char nonce[BRIDGE_NONCE_SIZE]);
int bridge_printable_token(const unsigned char *bytes, size_t length);
int bridge_equal_nonce(const unsigned char a[BRIDGE_NONCE_SIZE],
                       const unsigned char b[BRIDGE_NONCE_SIZE]);
void bridge_encode_length(uint32_t length, unsigned char out[4]);
uint32_t bridge_decode_length(const unsigned char in[4]);
int bridge_append_argument(wchar_t *command, size_t capacity, const wchar_t *argument);

#endif
