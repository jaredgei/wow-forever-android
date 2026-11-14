/* Synthetic test child; contains no real account or credential. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincrypt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

int wmain(int argc, WCHAR **argv) {
    if (argc != 3) return 2;
    HKEY key;
    if (RegOpenKeyExA(HKEY_CURRENT_USER,
        "Software\\Blizzard Entertainment\\Battle.net\\Launch Options\\WoW",
        0, KEY_QUERY_VALUE, &key)) return 3;
    BYTE buffer[8192], entropy_bytes[] = {
        0xc8,0x76,0xf4,0xae,0x4c,0x95,0x2e,0xfe,0xf2,0xfa,0x0f,0x54,0x19,0xc0,0x9c,0x43
    };
    DWORD size = sizeof(buffer), kind = 0;
    LONG status = RegQueryValueExA(key, "WEB_TOKEN", NULL, &kind, buffer, &size);
    if (!wcscmp(argv[1], L"--probe-clean")) {
        RegCloseKey(key);
        if (status != ERROR_FILE_NOT_FOUND) return 4;
    } else {
        if (status || kind != REG_BINARY) return 5;
        DATA_BLOB protected = {size, buffer}, plain = {0}, entropy = {16, entropy_bytes};
        if (!CryptUnprotectData(&protected, NULL, &entropy, NULL, NULL,
                               CRYPTPROTECT_UI_FORBIDDEN, &plain)) return 6;
        const char expected[] = "synthetic-unit-test-token";
        int correct = plain.cbData == sizeof(expected)-1 &&
                      !memcmp(plain.pbData, expected, sizeof(expected)-1);
        SecureZeroMemory(plain.pbData, plain.cbData);
        LocalFree(plain.pbData);
        SecureZeroMemory(buffer, sizeof(buffer));
        if (!correct) return 7;
        char timestamp[32];
        size = sizeof(timestamp);
        if (RegQueryValueExA(key, "ACCOUNT_TS", NULL, &kind, (BYTE *)timestamp, &size) ||
            kind != REG_SZ || !size || size > sizeof(timestamp) || timestamp[size-1] != 0) return 8;
        long long age = (long long)time(NULL) - _strtoi64(timestamp, NULL, 10);
        if (age < 0 || age > 60) return 9;
        size = sizeof(buffer);
        if (RegQueryValueExA(key, "GAME_ACCOUNT", NULL, NULL, buffer, &size) != ERROR_FILE_NOT_FOUND) return 10;
        size = sizeof(buffer);
        if (RegQueryValueExA(key, "REGION", NULL, NULL, buffer, &size) != ERROR_FILE_NOT_FOUND) return 11;
        RegCloseKey(key);
        /* Keep the child alive until the harness releases it. */
        unsigned attempts = 0;
        while (GetFileAttributesW(argv[2]) == INVALID_FILE_ATTRIBUTES && ++attempts < 200) Sleep(50);
        if (attempts == 200) return 12;
    }
    FILE *marker = _wfopen(argv[2], L"wb");
    if (!marker) return 13;
    fputs("OK", marker);
    fclose(marker);
    return 0;
}
