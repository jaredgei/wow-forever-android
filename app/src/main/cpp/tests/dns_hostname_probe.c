/* Exercise the compiled shim through the host's real libc resolver. */
#define _GNU_SOURCE
#include <assert.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(void)
{
    const char *expected = getenv("GAMENATIVE_HOSTNAME");
    char hostname[256], scratch[4096];
    struct addrinfo hints = { .ai_family = AF_INET, .ai_flags = AI_CANONNAME };
    struct addrinfo *addresses = NULL;
    struct hostent *host, *result = NULL, storage;
    int host_error = 0;
    assert(expected && *expected);
    assert(gethostname(hostname, sizeof(hostname)) == 0);
    assert(strcmp(hostname, expected) == 0);
    assert(getaddrinfo(hostname, NULL, &hints, &addresses) == 0);
    assert(addresses && addresses->ai_canonname);
    assert(strcmp(addresses->ai_canonname, expected) == 0);
    freeaddrinfo(addresses);
    host = gethostbyname(hostname);
    assert(host && strcmp(host->h_name, expected) == 0);
    assert(gethostbyname_r(hostname, &storage, scratch, sizeof(scratch),
                         &result, &host_error) == 0);
    assert(result && strcmp(result->h_name, expected) == 0);
    puts("Real-libc probe: all four hostname APIs agree");
    return 0;
}
