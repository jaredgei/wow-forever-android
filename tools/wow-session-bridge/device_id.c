/* Reproduce LoginBrowserClientHandler's Device-Id from the Wine prefix.
 * The result matched all 23 fields of the game's serialized logon request.
 * Source addresses: 110d6130, 110d6370, 110d6530, 110d6910, 110d6af0.
 * A fresh prefix receives its own persistent identity. Existing identities
 * are preserved. The result goes only to a captured pipe.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincrypt.h>
#include <iphlpapi.h>
#include <cpuid.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "device_id.h"

static const char identity_path[] = "Software\\Blizzard Entertainment\\Battle.net\\Identity";

/* 110d6130 uses SystemFunction036, then CryptGenRandom, to create 64 bytes.
 * Fail if both strong sources fail instead of using its time-seeded fallback. */
static int random_identity(BYTE identity[64]) {
    typedef BOOLEAN (WINAPI *RandomBytes)(PVOID, ULONG);
    HMODULE module = GetModuleHandleW(L"advapi32.dll");
    RandomBytes random_bytes = module
        ? (RandomBytes)(void *)GetProcAddress(module, "SystemFunction036") : NULL;
    if (random_bytes && random_bytes(identity, 64)) return 1;
    HCRYPTPROV provider;
    if (!CryptAcquireContextW(&provider, NULL, NULL, PROV_RSA_FULL,
                              CRYPT_VERIFYCONTEXT | CRYPT_SILENT)) return 0;
    BOOL generated = CryptGenRandom(provider, 64, identity);
    CryptReleaseContext(provider, 0);
    return generated != FALSE;
}

static LONG read_identity(HKEY key, BYTE identity[64]) {
    DWORD size = 64, kind = 0;
    LONG error = RegQueryValueExA(key, "Identity", NULL, &kind, identity, &size);
    if (error) return error;
    return kind == REG_BINARY && size == 64 ? ERROR_SUCCESS : ERROR_INVALID_DATA;
}

static int load_identity(BYTE identity[64]) {
    /* Helpers in the same Wine server must not race to create different seeds. */
    HANDLE mutex = CreateMutexW(NULL, FALSE, L"Local\\wowlauncher.device-identity");
    if (!mutex) return 0;
    DWORD wait = WaitForSingleObject(mutex, 30000);
    if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) {
        CloseHandle(mutex);
        return 0;
    }
    HKEY key = NULL;
    int result = 0;
    LONG error = RegOpenKeyExA(HKEY_CURRENT_USER, identity_path, 0, KEY_QUERY_VALUE, &key);
    if (error == ERROR_SUCCESS) {
        error = read_identity(key, identity);
        RegCloseKey(key);
        key = NULL;
        if (error == ERROR_SUCCESS) { result = 1; goto done; }
        if (error != ERROR_FILE_NOT_FOUND) goto done;
    } else if (error != ERROR_FILE_NOT_FOUND && error != ERROR_PATH_NOT_FOUND) goto done;
    error = RegCreateKeyExA(HKEY_CURRENT_USER, identity_path, 0, NULL, 0,
                            KEY_QUERY_VALUE | KEY_SET_VALUE, NULL, &key, NULL);
    if (error) goto done;
    /* Recheck after creating/opening the key, preserving an identity written
     * since the first lookup. Malformed values are never silently replaced. */
    error = read_identity(key, identity);
    if (error == ERROR_SUCCESS) { result = 1; goto done; }
    if (error != ERROR_FILE_NOT_FOUND || !random_identity(identity)) goto done;
    if (RegSetValueExA(key, "Identity", 0, REG_BINARY, identity, 64)) goto done;
    result = RegFlushKey(key) == ERROR_SUCCESS;
done:
    if (key) RegCloseKey(key);
    ReleaseMutex(mutex);
    CloseHandle(mutex);
    return result;
}

static uint32_t device_hash(const void *bytes, size_t size) {
    const unsigned char *p = bytes;
    uint32_t h = 0x811c9dc5;
    while (size--) h = (h ^ *p++) * 0x1000193;
    return h;
}

static uint32_t device_number(unsigned long long n, const char *format) {
    char buffer[64];
    if (strcmp(format, "%llu") == 0) snprintf(buffer, sizeof(buffer), "%llu", n);
    else if (strcmp(format, "%04X") == 0) snprintf(buffer, sizeof(buffer), "%04X", (unsigned)n);
    else if (strcmp(format, "%X") == 0) snprintf(buffer, sizeof(buffer), "%X", (unsigned)n);
    else snprintf(buffer, sizeof(buffer), "%d", (int)n);
    return device_hash(buffer, strlen(buffer));
}

int pipe_device(void) {
    static const char *names[] = {"RGKY","CPGE","ULNG","SLNG","CNME","UNME","UTCO",
        "CARC","CREV","CLVL","PMEM","PSZE","OVER","CVRA","CFTC","CFTD",
        "CEFC","CEFD","CBRD","CVEN","ANME","ADSC","MAC"};
    uint32_t value[23];
    for (int i = 0; i < 23; i++) value[i] = 0x811c9dc5;
    BYTE identity[64] = {0};
    if (!load_identity(identity)) {
        SecureZeroMemory(identity, sizeof(identity));
        return 1;
    }
    value[0] = device_hash(identity, sizeof(identity));
    SecureZeroMemory(identity, sizeof(identity));
    value[1] = device_number(GetACP(), "%04X");
    value[2] = device_number(GetUserDefaultLangID(), "%04X");
    value[3] = device_number(GetSystemDefaultLangID(), "%04X");
    WCHAR wide[512] = {0};
    DWORD size = 512;
    if (!GetComputerNameW(wide, &size)) return 1;
    /* The original hashes character-count bytes of the UTF-16 buffer. */
    value[4] = device_hash(wide, size);
    size = 512;
    if (!GetUserNameW(wide, &size) || !size) return 1;
    value[5] = device_hash(wide, size - 1);
    TIME_ZONE_INFORMATION zone;
    if (GetTimeZoneInformation(&zone) == TIME_ZONE_ID_INVALID) return 1;
    value[6] = device_number(-zone.Bias, "%d");
    SYSTEM_INFO system;
    GetNativeSystemInfo(&system);
    value[7] = device_number(system.wProcessorArchitecture, "%d");
    value[8] = device_number(system.wProcessorRevision, "%d");
    value[9] = device_number(system.wProcessorLevel, "%d");
    MEMORYSTATUSEX memory = {.dwLength = sizeof(memory)};
    if (!GlobalMemoryStatusEx(&memory)) return 1;
    value[10] = device_number(memory.ullTotalPhys >> 20, "%llu");
    value[11] = device_number(memory.ullTotalPageFile >> 20, "%llu");
    OSVERSIONINFOEXW version = {.dwOSVersionInfoSize = sizeof(version)};
    typedef LONG (WINAPI *RtlVersion)(OSVERSIONINFOEXW *);
    RtlVersion rtl = (RtlVersion)(void *)GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion");
    if (!rtl || rtl(&version)) return 1;
    char buffer[512];
    /* Source uses narrow %s with the Windows wide service-pack buffer. */
    snprintf(buffer, sizeof(buffer), "%lu.%lu.%lu.%lu %s", version.dwPlatformId,
        version.dwMajorVersion, version.dwMinorVersion, version.dwBuildNumber,
        (const char *)version.szCSDVersion);
    value[12] = device_hash(buffer, strlen(buffer));
    unsigned a, b, c, d;
    __cpuid(0, a, b, c, d);
    char vendor[13] = {0};
    memcpy(vendor, &b, 4); memcpy(vendor + 4, &d, 4); memcpy(vendor + 8, &c, 4);
    value[19] = device_hash(vendor, 12);
    if (a >= 1) {
        __cpuid(1, a, b, c, d);
        value[13] = device_number(a, "%X");
        value[14] = device_number(c, "%X");
        value[15] = device_number(d, "%X");
    }
    __cpuid(0x80000000, a, b, c, d);
    unsigned maximum = a;
    if (maximum >= 0x80000001) {
        __cpuid(0x80000001, a, b, c, d);
        if (!strcmp(vendor, "GenuineIntel")) { c &= 0xfffffffe; d &= 0xfffff7ff; }
        else if (!strcmp(vendor, "AuthenticAMD")) c &= 0xfffffffe;
        value[16] = device_number(c, "%X");
        value[17] = device_number(d, "%X");
    }
    if (maximum >= 0x80000004) {
        unsigned brand[13] = {0};
        for (unsigned i = 0; i < 3; i++) {
            __cpuid(0x80000002 + i, a, b, c, d);
            brand[i*4] = a; brand[i*4+1] = b; brand[i*4+2] = c; brand[i*4+3] = d;
        }
        value[18] = device_hash(brand, strlen((char *)brand));
    }
    ULONG bytes = sizeof(IP_ADAPTER_INFO);
    IP_ADAPTER_INFO *adapters = malloc(bytes);
    if (!adapters) return 1;
    ULONG status = GetAdaptersInfo(adapters, &bytes);
    if (status == ERROR_BUFFER_OVERFLOW) {
        free(adapters); adapters = malloc(bytes);
        if (!adapters) return 1;
        status = GetAdaptersInfo(adapters, &bytes);
    }
    if (!status) {
        value[20] = device_hash(adapters->AdapterName, strlen(adapters->AdapterName));
        value[21] = device_hash(adapters->Description, strlen(adapters->Description));
        char mac[32] = {0};
        size_t used = 0;
        for (unsigned i = 0; i < adapters->AddressLength && i < MAX_ADAPTER_ADDRESS_LENGTH; i++)
            used += snprintf(mac + used, sizeof(mac) - used, "%.2X", adapters->Address[i]);
        value[22] = device_hash(mac, used);
    }
    free(adapters);
    printf("{ ");
    for (int i = 0; i < 23; i++) printf("%s\"%s\" : %u", i ? ", " : "", names[i], value[i]);
    puts(" }");
    return 0;
}
