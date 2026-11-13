/* Host-side regression tests. No Android device, DNS server or route is needed. */
#define _GNU_SOURCE
#define gethostname shim_gethostname
#define gethostbyname shim_gethostbyname
#define gethostbyname_r shim_gethostbyname_r
#define getaddrinfo shim_getaddrinfo
#define freeaddrinfo shim_freeaddrinfo
#define res_query shim_res_query
#define socket test_socket
#define connect test_connect
#define getsockname test_getsockname
#define close test_close
#include "../gamenative_dns_v4mapped.c"
#undef gethostname
#undef gethostbyname
#undef gethostbyname_r
#undef getaddrinfo
#undef freeaddrinfo
#undef res_query
#undef socket
#undef connect
#undef getsockname
#undef close

#include <assert.h>

#define HOSTNAME "DESKTOP-TEST123"
#define NATIVE_BUFFER_SIZE 64

static __thread struct hostent native_host;
static __thread char native_name[NI_MAXHOST];
static __thread char native_address[4];
static __thread char *native_addresses[2];
static __thread char *native_aliases[1];
static __thread char last_query[NI_MAXHOST];
static __thread size_t last_buflen;
static const char *route_address = "192.0.2.10";
static int route_available = 1;
static int native_error;
static int native_no_result;
static int checks;

#define CHECK(condition) do { \
    ++checks; \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition); \
        exit(1); \
    } \
} while (0)

int test_socket(int domain, int type, int protocol)
{
    (void)domain; (void)type; (void)protocol;
    return route_available ? 100 : -1;
}

int test_connect(int fd, const struct sockaddr *address, socklen_t length)
{
    (void)fd; (void)address; (void)length;
    return 0;
}

int test_getsockname(int fd, struct sockaddr *address, socklen_t *length)
{
    struct sockaddr_in *ipv4 = (struct sockaddr_in *)address;
    (void)fd;
    assert(*length >= sizeof(*ipv4));
    ipv4->sin_family = AF_INET;
    assert(inet_pton(AF_INET, route_address, &ipv4->sin_addr) == 1);
    *length = sizeof(*ipv4);
    return 0;
}

int test_close(int fd)
{
    (void)fd;
    return 0;
}

static struct hostent *native_lookup(const char *name)
{
    snprintf(last_query, sizeof(last_query), "%s", name);
    if (native_error) {
        h_errno = HOST_NOT_FOUND;
        errno = native_error;
        return NULL;
    }
    snprintf(native_name, sizeof(native_name), "%s", name);
    if (inet_pton(AF_INET, name, native_address) != 1)
        assert(inet_pton(AF_INET, "198.51.100.7", native_address) == 1);
    native_addresses[0] = native_address;
    native_addresses[1] = NULL;
    native_aliases[0] = NULL;
    native_host = (struct hostent) {
        .h_name = native_name, .h_aliases = native_aliases,
        .h_addrtype = AF_INET, .h_length = 4, .h_addr_list = native_addresses,
    };
    return &native_host;
}

static int native_lookup_r(const char *name, struct hostent *ret, char *buf,
                           size_t buflen, struct hostent **result, int *herr)
{
    struct hostent *native;
    last_buflen = buflen;
    *result = NULL;
    if (buflen < NATIVE_BUFFER_SIZE) {
        *herr = NETDB_INTERNAL;
        return ERANGE;
    }
    native = native_lookup(name);
    if (!native) {
        *herr = HOST_NOT_FOUND;
        return native_error;
    }
    if (native_no_result) {
        *herr = HOST_NOT_FOUND;
        return 0;
    }
    memset(buf, 0x5a, buflen);
    snprintf(buf, buflen, "%s", native->h_name);
    *ret = *native;
    ret->h_name = buf;
    *result = ret;
    *herr = 0;
    return 0;
}

static void noop(void) {}

static void test_legacy_name(void)
{
    struct hostent *host = shim_gethostbyname(HOSTNAME);
    CHECK(host != NULL);
    CHECK(strcmp(host->h_name, HOSTNAME) == 0);
    CHECK(strcmp(last_query, route_address) == 0);
    CHECK(strcmp(native_host.h_name, route_address) == 0);
    CHECK(host != &native_host);
    CHECK(host->h_addr_list == native_host.h_addr_list);
    CHECK(host->h_aliases == native_host.h_aliases);

    host = shim_gethostbyname("desktop-test123");
    CHECK(host != NULL && strcmp(host->h_name, HOSTNAME) == 0);
    route_address = "192.0.2.20";
    host = shim_gethostbyname(HOSTNAME);
    CHECK(host != NULL && strcmp(host->h_name, HOSTNAME) == 0);
    CHECK(strcmp(last_query, "192.0.2.20") == 0);
    CHECK((unsigned char)host->h_addr_list[0][3] == 20);

    route_available = 0;
    host = shim_gethostbyname(HOSTNAME);
    CHECK(host != NULL && strcmp(host->h_name, HOSTNAME) == 0);
    CHECK(strcmp(last_query, "127.0.0.1") == 0);
    route_available = 1;
}

static void test_reentrant_name(void)
{
    enum { REQUIRED = NATIVE_BUFFER_SIZE + sizeof(HOSTNAME) };
    unsigned char guarded[REQUIRED + 2];
    struct hostent host, *result;
    int herr, status;
    memset(guarded, 0x81, sizeof(guarded));
    char *buf = (char *)guarded + 1;
    result = (struct hostent *)1;
    status = shim_gethostbyname_r("desktop-test123", &host, buf, REQUIRED, &result, &herr);
    CHECK(status == 0 && result == &host && herr == 0);
    CHECK(last_buflen == NATIVE_BUFFER_SIZE);
    CHECK(host.h_name == buf + NATIVE_BUFFER_SIZE);
    CHECK(strcmp(host.h_name, HOSTNAME) == 0);
    CHECK(strcmp(buf, route_address) == 0);
    CHECK(guarded[0] == 0x81 && guarded[sizeof(guarded) - 1] == 0x81);

    for (size_t length = 0; length < REQUIRED; ++length) {
        result = (struct hostent *)1;
        status = shim_gethostbyname_r(HOSTNAME, &host, buf, length, &result, &herr);
        CHECK(status == ERANGE && result == NULL && herr == NETDB_INTERNAL);
    }

    char second[REQUIRED];
    struct hostent second_host, *second_result;
    status = shim_gethostbyname_r(HOSTNAME, &second_host, second, sizeof(second), &second_result, &herr);
    CHECK(status == 0 && second_result == &second_host);
    CHECK(strcmp(host.h_name, HOSTNAME) == 0);
    CHECK(strcmp(second_host.h_name, HOSTNAME) == 0);
    CHECK(second_host.h_name != host.h_name);

    /* A caller may use its output buffer to hold the input name. */
    strcpy(second, HOSTNAME);
    CHECK(shim_gethostbyname_r(second, &second_host, second, sizeof(second), &second_result, &herr) == 0);
    CHECK(strcmp(second_host.h_name, HOSTNAME) == 0);
}

static void test_passthrough_and_errors(void)
{
    char buf[128];
    struct hostent host, *result;
    int herr;
    CHECK(shim_gethostbyname("example.test") == &native_host);
    CHECK(strcmp(last_query, "example.test") == 0);
    CHECK(shim_gethostbyname_r("example.test", &host, buf, sizeof(buf), &result, &herr) == 0);
    CHECK(last_buflen == sizeof(buf));
    CHECK(strcmp(host.h_name, "example.test") == 0);

    CHECK(shim_gethostbyname("198.51.100.8") == &native_host);
    CHECK(strcmp(native_host.h_name, "198.51.100.8") == 0);
    CHECK(shim_gethostbyname_r("198.51.100.8", &host, buf, sizeof(buf), &result, &herr) == 0);
    CHECK(last_buflen == sizeof(buf) && strcmp(host.h_name, "198.51.100.8") == 0);

    unsetenv("GAMENATIVE_HOSTNAME");
    CHECK(shim_gethostbyname(HOSTNAME) == &native_host);
    CHECK(strcmp(last_query, HOSTNAME) == 0);
    CHECK(shim_gethostbyname_r(HOSTNAME, &host, buf, sizeof(buf), &result, &herr) == 0);
    CHECK(last_buflen == sizeof(buf) && host.h_name == buf);
    setenv("GAMENATIVE_HOSTNAME", HOSTNAME, 1);

    native_error = ENOENT;
    CHECK(shim_gethostbyname(HOSTNAME) == NULL);
    CHECK(h_errno == HOST_NOT_FOUND && errno == ENOENT);
    CHECK(shim_gethostbyname_r(HOSTNAME, &host, buf, sizeof(buf), &result, &herr) == ENOENT);
    CHECK(result == NULL && herr == HOST_NOT_FOUND);
    native_error = 0;
    native_no_result = 1;
    CHECK(shim_gethostbyname_r(HOSTNAME, &host, buf, sizeof(buf), &result, &herr) == 0);
    CHECK(result == NULL && herr == HOST_NOT_FOUND);
    native_no_result = 0;
}

static void test_name_capacity(void)
{
    char name[NI_MAXHOST + 1];
    memset(name, 'A', sizeof(name));
    name[NI_MAXHOST - 1] = 0;
    setenv("GAMENATIVE_HOSTNAME", name, 1);
    struct hostent *host = shim_gethostbyname(name);
    CHECK(host && strcmp(host->h_name, name) == 0);
    name[NI_MAXHOST - 1] = 'A';
    name[NI_MAXHOST] = 0;
    setenv("GAMENATIVE_HOSTNAME", name, 1);
    CHECK(shim_gethostbyname(name) == NULL);
    CHECK(h_errno == NO_RECOVERY && errno == ERANGE);
    setenv("GAMENATIVE_HOSTNAME", HOSTNAME, 1);
}

static pthread_barrier_t barrier;
static struct hostent *worker_host;
static void *lookup_thread(void *unused)
{
    (void)unused;
    struct hostent *host = shim_gethostbyname("desktop-test123");
    assert(host && strcmp(host->h_name, HOSTNAME) == 0);
    worker_host = host;
    pthread_barrier_wait(&barrier);
    pthread_barrier_wait(&barrier);
    assert(strcmp(host->h_name, HOSTNAME) == 0);
    return NULL;
}

static void test_thread_storage(void)
{
    pthread_t thread;
    pthread_barrier_init(&barrier, NULL, 2);
    CHECK(pthread_create(&thread, NULL, lookup_thread, NULL) == 0);
    pthread_barrier_wait(&barrier);
    struct hostent *host = shim_gethostbyname(HOSTNAME);
    CHECK(host && strcmp(host->h_name, HOSTNAME) == 0);
    CHECK(host != worker_host);
    pthread_barrier_wait(&barrier);
    CHECK(pthread_join(thread, NULL) == 0);
    pthread_barrier_destroy(&barrier);
}

int main(void)
{
    pthread_once(&resolver_once, noop);
    next_gethostbyname = native_lookup;
    next_gethostbyname_r = native_lookup_r;
    setenv("GAMENATIVE_HOSTNAME", HOSTNAME, 1);
    test_legacy_name();
    test_reentrant_name();
    test_passthrough_and_errors();
    test_name_capacity();
    test_thread_storage();
    printf("DNS hostname regression tests: %d checks passed\n", checks);
    return 0;
}
