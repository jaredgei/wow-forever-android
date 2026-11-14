/* WoW Forever's one-shot, loopback-only Wine session handoff.
 * The token is received in memory and protected in the game's Wine prefix.
 * No credential is accepted in an argument, environment variable, or file. */
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0601
#include <winsock2.h>
#include <windows.h>
#include <wincrypt.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include "device_id.h"
#include "protocol.h"

static BYTE entropy_bytes[16] = {
    0xc8, 0x76, 0xf4, 0xae, 0x4c, 0x95, 0x2e, 0xfe,
    0xf2, 0xfa, 0x0f, 0x54, 0x19, 0xc0, 0x9c, 0x43
};
static char device_json[BRIDGE_DEVICE_LIMIT];
static size_t device_used;
static int device_overflow;

/* Only device_id.c is compiled with printf/puts redirected to these bounded
 * sinks. Its fingerprint algorithm remains the upstream file byte-for-byte. */
int bridge_device_printf(const char *format, ...) {
    if (device_overflow) return -1;
    va_list arguments;
    va_start(arguments, format);
    int count = vsnprintf(device_json + device_used, sizeof(device_json) - device_used,
                          format, arguments);
    va_end(arguments);
    if (count < 0 || (size_t)count >= sizeof(device_json) - device_used) {
        device_overflow = 1;
        return -1;
    }
    device_used += (size_t)count;
    return count;
}

int bridge_device_puts(const char *value) {
    return bridge_device_printf("%s\n", value);
}

static int read_config(const WCHAR *path, unsigned short *port, BYTE nonce[BRIDGE_NONCE_SIZE]) {
    BYTE bytes[128];
    DWORD count = 0;
    HANDLE file = CreateFileW(path, GENERIC_READ | DELETE, 0, NULL, OPEN_EXISTING,
                               FILE_FLAG_DELETE_ON_CLOSE, NULL);
    if (file == INVALID_HANDLE_VALUE) return 0;
    BOOL read = ReadFile(file, bytes, sizeof(bytes), &count, NULL);
    CloseHandle(file); /* The private bootstrap config is removed even when malformed. */
    int ok = read && bridge_parse_config(bytes, count, port, nonce);
    SecureZeroMemory(bytes, sizeof(bytes));
    return ok;
}

static int transfer(SOCKET peer, void *bytes, size_t length, int sending) {
    char *cursor = bytes;
    while (length) {
        int count = sending ? send(peer, cursor, (int)length, 0)
                            : recv(peer, cursor, (int)length, 0);
        if (count <= 0) return 0;
        cursor += count;
        length -= (size_t)count;
    }
    return 1;
}

static int send_frame(SOCKET peer, const void *bytes, uint32_t length) {
    BYTE header[4];
    bridge_encode_length(length, header);
    return transfer(peer, header, sizeof(header), 1) &&
           transfer(peer, (void *)bytes, length, 1);
}

static int receive_token(SOCKET peer, BYTE token[BRIDGE_TOKEN_LIMIT], DWORD *length) {
    BYTE header[4];
    if (!transfer(peer, header, sizeof(header), 0)) return 0;
    uint32_t size = bridge_decode_length(header);
    if (size == 0 || size > BRIDGE_TOKEN_LIMIT || !transfer(peer, token, size, 0) ||
        !bridge_printable_token(token, size)) return 0;
    *length = size;
    return 1;
}

static int make_proof(const char *domain, const BYTE nonce[BRIDGE_NONCE_SIZE], BYTE proof[32]) {
    HCRYPTPROV provider = 0;
    HCRYPTHASH hash = 0;
    DWORD size = 32;
    int ok = CryptAcquireContextW(&provider, NULL, NULL, PROV_RSA_AES,
                                  CRYPT_VERIFYCONTEXT | CRYPT_SILENT) &&
             CryptCreateHash(provider, CALG_SHA_256, 0, 0, &hash) &&
             CryptHashData(hash, (const BYTE *)domain, (DWORD)strlen(domain), 0) &&
             CryptHashData(hash, nonce, BRIDGE_NONCE_SIZE, 0) &&
             CryptGetHashParam(hash, HP_HASHVAL, proof, &size, 0) && size == 32;
    if (hash) CryptDestroyHash(hash);
    if (provider) CryptReleaseContext(provider, 0);
    return ok;
}

static int remove_value(HKEY key, const char *name) {
    LONG result = RegDeleteValueA(key, name);
    return result == ERROR_SUCCESS || result == ERROR_FILE_NOT_FOUND;
}

static int no_login_file(const WCHAR *directory) {
    WCHAR path[32768];
    size_t length = wcslen(directory);
    if (length + 11 >= sizeof(path) / sizeof(path[0])) return 0;
    wcscpy(path, directory);
    wcscat(path, L"\\login.txt");
    if (GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES) return 0;
    return GetLastError() == ERROR_FILE_NOT_FOUND;
}

int wmain(int argc, WCHAR **argv) {
    unsigned short port = 0;
    BYTE nonce[BRIDGE_NONCE_SIZE] = {0}, client_proof[32] = {0};
    BYTE server_proof[32] = {0}, received_proof[32] = {0};
    BYTE token[BRIDGE_TOKEN_LIMIT] = {0};
    DWORD token_size = 0;
    DATA_BLOB protected_token = {0};
    DATA_BLOB entropy = {sizeof(entropy_bytes), entropy_bytes};
    HKEY key = NULL;
    HANDLE mutex = NULL;
    SOCKET peer = INVALID_SOCKET;
    PROCESS_INFORMATION child = {0};
    int result = 2, winsock_started = 0, mutex_owned = 0, authenticated = 0, owns_token = 0;
    WCHAR directory[32768], command[BRIDGE_COMMAND_LIMIT] = {0};
    if (argc < 3 || !read_config(argv[1], &port, nonce)) goto done;

    /* The registry handoff is shared by all games in this Wine user/prefix.
     * Hold ownership until the child exits so one launch cannot replace another. */
    result = 3;
    mutex = CreateMutexW(NULL, FALSE, L"Local\\WoWForever.SessionBridge");
    if (!mutex) goto done;
    DWORD lock = WaitForSingleObject(mutex, 0);
    if (lock != WAIT_OBJECT_0 && lock != WAIT_ABANDONED) goto done;
    mutex_owned = 1;

    result = 4;
    WSADATA socket_data;
    if (WSAStartup(MAKEWORD(2, 2), &socket_data)) goto done;
    winsock_started = 1;
    peer = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (peer == INVALID_SOCKET) goto done;
    DWORD timeout = 15 * 60 * 1000;
    if (setsockopt(peer, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof(timeout)) ||
        setsockopt(peer, SOL_SOCKET, SO_SNDTIMEO, (char *)&timeout, sizeof(timeout))) goto done;
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(port);
    result = 5;
    if (connect(peer, (struct sockaddr *)&address, sizeof(address))) goto done;
    result = 6;
    if (!make_proof("WOW-SESSION-CLIENT-1", nonce, client_proof) ||
        !make_proof("WOW-SESSION-SERVER-1", nonce, server_proof)) goto done;
    SecureZeroMemory(nonce, sizeof(nonce));
    if (!transfer(peer, client_proof, sizeof(client_proof), 1) ||
        !transfer(peer, received_proof, sizeof(received_proof), 0) ||
        !bridge_equal_nonce(server_proof, received_proof)) goto done;
    authenticated = 1;
    SecureZeroMemory(client_proof, sizeof(client_proof));
    SecureZeroMemory(server_proof, sizeof(server_proof));
    SecureZeroMemory(received_proof, sizeof(received_proof));

    result = 7;
    if (pipe_device() || device_overflow || !device_used ||
        !send_frame(peer, device_json, (uint32_t)device_used)) goto done;
    SecureZeroMemory(device_json, sizeof(device_json));
    result = 8;
    if (!receive_token(peer, token, &token_size)) goto done;

    result = 9;
    if (wcslen(argv[2]) >= sizeof(directory) / sizeof(directory[0])) goto done;
    wcscpy(directory, argv[2]);
    WCHAR *separator = wcsrchr(directory, L'\\');
    if (!separator || separator == directory) goto done;
    *separator = 0;
    /* This client gives login.txt precedence over the launcher token path. */
    if (!no_login_file(directory)) goto done;
    for (int i = 2; i < argc; i++)
        if (!bridge_append_argument(command, BRIDGE_COMMAND_LIMIT, argv[i])) goto done;

    result = 10;
    DATA_BLOB plaintext = {token_size, token};
    if (!CryptProtectData(&plaintext, NULL, &entropy, NULL, NULL,
                           CRYPTPROTECT_UI_FORBIDDEN, &protected_token)) goto done;
    SecureZeroMemory(token, sizeof(token));
    result = 11;
    if (RegCreateKeyExA(HKEY_CURRENT_USER,
        "Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\WoW", 0, NULL,
        0, KEY_QUERY_VALUE | KEY_SET_VALUE, NULL, &key, NULL)) goto done;
    if (RegSetValueExA(key, "WEB_TOKEN", 0, REG_BINARY,
                       protected_token.pbData, protected_token.cbData)) goto done;
    owns_token = 1;
    SecureZeroMemory(protected_token.pbData, protected_token.cbData);
    LocalFree(protected_token.pbData);
    protected_token.pbData = NULL;
    char timestamp[32];
    time_t now = time(NULL);
    if (now < 0) goto done;
    snprintf(timestamp, sizeof(timestamp), "%lld", (long long)now);
    if (RegSetValueExA(key, "ACCOUNT_TS", 0, REG_SZ,
                       (BYTE *)timestamp, (DWORD)strlen(timestamp) + 1) ||
        !remove_value(key, "GAME_ACCOUNT") || !remove_value(key, "REGION") || RegFlushKey(key)) goto done;

    result = 12;
    STARTUPINFOW startup = {.cb = sizeof(startup)};
    /* No handles or credentials from the transport are inherited by the game. */
    if (!CreateProcessW(argv[2], command, NULL, NULL, FALSE, DETACHED_PROCESS,
                         NULL, directory, &startup, &child)) goto done;
    (void)send_frame(peer, "STARTED", 7);
    closesocket(peer);
    peer = INVALID_SOCKET;
    /* The outer Wine launcher tracks this helper, so keep it alive for the game. */
    WaitForSingleObject(child.hProcess, INFINITE);
    result = 0;

done:
    SecureZeroMemory(nonce, sizeof(nonce));
    SecureZeroMemory(client_proof, sizeof(client_proof));
    SecureZeroMemory(server_proof, sizeof(server_proof));
    SecureZeroMemory(received_proof, sizeof(received_proof));
    SecureZeroMemory(token, sizeof(token));
    SecureZeroMemory(device_json, sizeof(device_json));
    if (protected_token.pbData) {
        SecureZeroMemory(protected_token.pbData, protected_token.cbData);
        LocalFree(protected_token.pbData);
    }
    if (key) {
        if (owns_token) {
            remove_value(key, "WEB_TOKEN");
            remove_value(key, "ACCOUNT_TS");
            RegFlushKey(key);
        }
        RegCloseKey(key);
    }
    if (peer != INVALID_SOCKET) {
        if (result && authenticated) {
            char message[24];
            int length = snprintf(message, sizeof(message), "ERROR:%d", result);
            if (length > 0) (void)send_frame(peer, message, (uint32_t)length);
        }
        closesocket(peer);
    }
    if (child.hThread) CloseHandle(child.hThread);
    if (child.hProcess) CloseHandle(child.hProcess);
    if (winsock_started) WSACleanup();
    if (mutex_owned) ReleaseMutex(mutex);
    if (mutex) CloseHandle(mutex);
    return result;
}
