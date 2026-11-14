/*
 * Android's getaddrinfo() rejects AI_V4MAPPED, while Windows applications
 * commonly use AF_INET6 + AI_V4MAPPED with a dual-stack socket.  Provide the
 * Windows fallback for IPv4-only hosts by returning IPv4-mapped IPv6 results.
 *
 * Android also reports the machine hostname as "localhost", so applications
 * that discover their own address via getaddrinfo(gethostname()) - notably
 * Epic Online Services' P2P stack - resolve 127.0.0.1 and bind their gameplay
 * sockets to loopback, which breaks online multiplayer.  gethostname() is
 * interposed below to report the device's routable address instead.
 *
 * When GAMENATIVE_HOSTNAME is set, gethostname() reports that name instead,
 * and the resolvers below resolve it to the routable address, so the Windows
 * computer name stays the same across networks instead of following the IP.
 *
 * This shim is preloaded for every bionic launch.
 */

#define _GNU_SOURCE

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

#define DNS_LOG(format, ...) \
    dprintf(STDERR_FILENO, "[GameNativeDNS] " format "\n", ##__VA_ARGS__)

typedef uint64_t android_net_handle_t;

static int (*android_res_nquery_fn)(android_net_handle_t, const char *, int, int,
                                    uint32_t);
static int (*android_res_nresult_fn)(int, int *, uint8_t *, size_t);
static int (*android_getaddrinfofornetwork_fn)(android_net_handle_t,
                                               const char *, const char *,
                                               const struct addrinfo *,
                                               struct addrinfo **);
static pthread_once_t android_resolver_once = PTHREAD_ONCE_INIT;

static void resolve_android_resolver(void)
{
    void *handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);

    if (!handle)
    {
        DNS_LOG("dlopen(libandroid.so) failed: %s", dlerror());
        return;
    }

    android_res_nquery_fn = dlsym(handle, "android_res_nquery");
    android_res_nresult_fn = dlsym(handle, "android_res_nresult");
    android_getaddrinfofornetwork_fn =
        dlsym(handle, "android_getaddrinfofornetwork");
    if (!android_res_nquery_fn || !android_res_nresult_fn)
        DNS_LOG("Android async resolver symbols are unavailable");
}

static int rcode_to_h_errno(int rcode)
{
    switch (rcode)
    {
        case 2:  return TRY_AGAIN;       /* SERVFAIL */
        case 3:  return HOST_NOT_FOUND;  /* NXDOMAIN */
        case 0:  return NO_DATA;
        default: return NO_RECOVERY;
    }
}

/*
 * Wine's dnsapi unixlib calls res_query().  Bionic exposes that legacy API,
 * but it does not reliably carry the Android application network context.
 * Route the query through Android's public asynchronous resolver instead.
 */
int res_query(const char *name, int dns_class, int type,
              unsigned char *answer, int answer_length)
{
    struct pollfd query_poll;
    int fd, poll_result, rcode = 0, result;

    pthread_once(&android_resolver_once, resolve_android_resolver);
    if (!android_res_nquery_fn || !android_res_nresult_fn)
    {
        h_errno = NO_RECOVERY;
        errno = ENOSYS;
        return -1;
    }

    fd = android_res_nquery_fn(0, name, dns_class, type, 0);
    if (fd < 0)
    {
        h_errno = TRY_AGAIN;
        errno = -fd;
        DNS_LOG("android_res_nquery(%s, type=%d) failed: %d", name, type, fd);
        return -1;
    }

    query_poll.fd = fd;
    query_poll.events = POLLIN;
    query_poll.revents = 0;
    do
    {
        poll_result = poll(&query_poll, 1, 30000);
    } while (poll_result < 0 && errno == EINTR);

    if (poll_result <= 0)
    {
        int saved_errno = poll_result == 0 ? ETIMEDOUT : errno;
        close(fd);
        h_errno = TRY_AGAIN;
        errno = saved_errno;
        DNS_LOG("DNS query for %s timed out or poll failed: %d", name,
                saved_errno);
        return -1;
    }

    result = android_res_nresult_fn(fd, &rcode, answer, (size_t)answer_length);
    if (result < 0 || rcode)
    {
        h_errno = result < 0 ? TRY_AGAIN : rcode_to_h_errno(rcode);
        if (result < 0) errno = -result;
        DNS_LOG("DNS result for %s failed: result=%d rcode=%d", name, result,
                rcode);
        return -1;
    }

    DNS_LOG("resolved %s type=%d (%d-byte reply)", name, type, result);
    return result;
}

struct mapped_result
{
    struct addrinfo *head;
    struct mapped_result *next;
};

static int (*next_getaddrinfo)(const char *, const char *, const struct addrinfo *,
                               struct addrinfo **);
static void (*next_freeaddrinfo)(struct addrinfo *);
static int (*next_gethostname)(char *, size_t);
static struct hostent *(*next_gethostbyname)(const char *);
static int (*next_gethostbyname_r)(const char *, struct hostent *, char *, size_t,
                                   struct hostent **, int *);
static pthread_once_t resolver_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t mapped_results_lock = PTHREAD_MUTEX_INITIALIZER;
static struct mapped_result *mapped_results;

static void resolve_next_symbols(void)
{
    next_getaddrinfo = dlsym(RTLD_NEXT, "getaddrinfo");
    next_freeaddrinfo = dlsym(RTLD_NEXT, "freeaddrinfo");
    next_gethostname = dlsym(RTLD_NEXT, "gethostname");
    next_gethostbyname = dlsym(RTLD_NEXT, "gethostbyname");
    next_gethostbyname_r = dlsym(RTLD_NEXT, "gethostbyname_r");
}

static int network_getaddrinfo(const char *node, const char *service,
                               const struct addrinfo *hints,
                               struct addrinfo **result)
{
    int ret;

    pthread_once(&android_resolver_once, resolve_android_resolver);
    if (android_getaddrinfofornetwork_fn)
        ret = android_getaddrinfofornetwork_fn(0, node, service, hints, result);
    else
        ret = next_getaddrinfo(node, service, hints, result);

    if (node && (!hints || !(hints->ai_flags & AI_NUMERICHOST)))
        DNS_LOG("getaddrinfo(%s, family=%d, flags=0x%x) -> %d", node,
                hints ? hints->ai_family : AF_UNSPEC,
                hints ? hints->ai_flags : 0, ret);
    return ret;
}

static void free_mapped_list(struct addrinfo *head)
{
    while (head)
    {
        struct addrinfo *next = head->ai_next;
        free(head->ai_canonname);
        free(head->ai_addr);
        free(head);
        head = next;
    }
}

static int track_mapped_list(struct addrinfo *head)
{
    struct mapped_result *entry = malloc(sizeof(*entry));
    if (!entry) return EAI_MEMORY;

    entry->head = head;
    pthread_mutex_lock(&mapped_results_lock);
    entry->next = mapped_results;
    mapped_results = entry;
    pthread_mutex_unlock(&mapped_results_lock);
    return 0;
}

static int map_ipv4_results(const struct addrinfo *ipv4, struct addrinfo **result)
{
    struct addrinfo *head = NULL;
    struct addrinfo **tail = &head;

    for (; ipv4; ipv4 = ipv4->ai_next)
    {
        const struct sockaddr_in *source;
        struct sockaddr_in6 *address;
        struct addrinfo *entry;

        if (ipv4->ai_family != AF_INET ||
            ipv4->ai_addrlen < (socklen_t)sizeof(*source))
            continue;

        entry = calloc(1, sizeof(*entry));
        address = calloc(1, sizeof(*address));
        if (!entry || !address)
        {
            free(entry);
            free(address);
            free_mapped_list(head);
            return EAI_MEMORY;
        }

        source = (const struct sockaddr_in *)ipv4->ai_addr;
        address->sin6_family = AF_INET6;
        address->sin6_port = source->sin_port;
        address->sin6_addr.s6_addr[10] = 0xff;
        address->sin6_addr.s6_addr[11] = 0xff;
        memcpy(&address->sin6_addr.s6_addr[12], &source->sin_addr,
               sizeof(source->sin_addr));

        entry->ai_flags = ipv4->ai_flags;
        entry->ai_family = AF_INET6;
        entry->ai_socktype = ipv4->ai_socktype;
        entry->ai_protocol = ipv4->ai_protocol;
        entry->ai_addrlen = sizeof(*address);
        entry->ai_addr = (struct sockaddr *)address;
        if (ipv4->ai_canonname)
        {
            entry->ai_canonname = strdup(ipv4->ai_canonname);
            if (!entry->ai_canonname)
            {
                free_mapped_list(entry);
                free_mapped_list(head);
                return EAI_MEMORY;
            }
        }

        *tail = entry;
        tail = &entry->ai_next;
    }

    if (!head) return EAI_NONAME;
    if (track_mapped_list(head))
    {
        free_mapped_list(head);
        return EAI_MEMORY;
    }

    *result = head;
    return 0;
}

static int routable_address(char *address, size_t len);
static int lookup(const char *node, const char *service,
                  const struct addrinfo *hints, struct addrinfo **result);

static const char *configured_hostname(void)
{
    const char *hostname = getenv("GAMENATIVE_HOSTNAME");
    return hostname && hostname[0] ? hostname : NULL;
}

/*
 * The configured hostname isn't in DNS, so lookups of it are answered with
 * the routable address instead.  Returns [name] for any other name.
 */
static const char *local_name(const char *name, char *address, size_t len)
{
    const char *hostname = configured_hostname();

    if (!name || !hostname || strcasecmp(name, hostname) != 0)
        return name;
    if (routable_address(address, len) != 0)
        snprintf(address, len, "127.0.0.1");
    return address;
}

/*
 * The configured hostname's canonical name is the hostname itself: Wine
 * builds the computer name from it, splitting the domain off at the first dot.
 */
int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **result)
{
    char address[INET_ADDRSTRLEN];
    const char *resolved = local_name(node, address, sizeof(address));
    struct addrinfo *entry;
    char *canonname;
    int ret;

    ret = lookup(resolved, service, hints, result);
    if (ret || resolved == node || !hints || !(hints->ai_flags & AI_CANONNAME))
        return ret;

    for (entry = *result; entry; entry = entry->ai_next)
    {
        if (!entry->ai_canonname) continue;
        canonname = strdup(node);
        if (!canonname) continue;
        free(entry->ai_canonname);
        entry->ai_canonname = canonname;
    }
    return 0;
}

static int lookup(const char *node, const char *service,
                  const struct addrinfo *hints, struct addrinfo **result)
{
    struct addrinfo fallback_hints;
    struct addrinfo *ipv4 = NULL;
    int ret;

    pthread_once(&resolver_once, resolve_next_symbols);
    if (!next_getaddrinfo)
    {
        errno = ENOSYS;
        return EAI_SYSTEM;
    }

    if (hints && hints->ai_family != AF_INET6 &&
        (hints->ai_flags & (AI_V4MAPPED | AI_ALL)))
    {
        /*
         * WinSock accepts AI_V4MAPPED with AF_UNSPEC and simply ignores it.
         * Bionic instead rejects the whole request with EAI_BADFLAGS.
         */
        fallback_hints = *hints;
        fallback_hints.ai_flags &= ~(AI_V4MAPPED | AI_ALL);
        return network_getaddrinfo(node, service, &fallback_hints, result);
    }

    if (!hints || hints->ai_family != AF_INET6 ||
        !(hints->ai_flags & AI_V4MAPPED))
        return network_getaddrinfo(node, service, hints, result);

    fallback_hints = *hints;
    fallback_hints.ai_flags &= ~(AI_V4MAPPED | AI_ALL);

    /* Prefer a genuine IPv6 answer, as Windows does without AI_ALL. */
    ret = network_getaddrinfo(node, service, &fallback_hints, result);
    if (!ret) return 0;

    fallback_hints.ai_family = AF_INET;
    ret = network_getaddrinfo(node, service, &fallback_hints, &ipv4);
    if (ret) return ret;

    ret = map_ipv4_results(ipv4, result);
    next_freeaddrinfo(ipv4);
    return ret;
}

void freeaddrinfo(struct addrinfo *result)
{
    struct mapped_result **cursor;
    struct mapped_result *matched = NULL;

    pthread_once(&resolver_once, resolve_next_symbols);

    pthread_mutex_lock(&mapped_results_lock);
    for (cursor = &mapped_results; *cursor; cursor = &(*cursor)->next)
    {
        if ((*cursor)->head == result)
        {
            matched = *cursor;
            *cursor = matched->next;
            break;
        }
    }
    pthread_mutex_unlock(&mapped_results_lock);

    if (matched)
    {
        free_mapped_list(matched->head);
        free(matched);
    }
    else if (next_freeaddrinfo)
    {
        next_freeaddrinfo(result);
    }
}

/*
 * Connecting a UDP socket reveals the source address the kernel would use,
 * without the interface enumeration the app sandbox filters.  connect() on a
 * datagram socket sends no packets.
 */
static int routable_address(char *address, size_t len)
{
    struct sockaddr_in probe;
    socklen_t probe_len = sizeof(probe);
    int fd;

    fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0)
        return -1;

    memset(&probe, 0, sizeof(probe));
    probe.sin_family = AF_INET;
    probe.sin_port = htons(53);
    probe.sin_addr.s_addr = htonl(0x08080808); /* any routable destination */
    if (connect(fd, (const struct sockaddr *)&probe, sizeof(probe)) != 0 ||
        getsockname(fd, (struct sockaddr *)&probe, &probe_len) != 0)
    {
        close(fd);
        return -1;
    }
    close(fd);

    if (probe.sin_addr.s_addr == 0 ||
        (ntohl(probe.sin_addr.s_addr) >> 24) == 127)
        return -1;
    if (!inet_ntop(AF_INET, &probe.sin_addr, address, len))
        return -1;
    return 0;
}

struct hostent *gethostbyname(const char *name)
{
    static __thread struct hostent local_host;
    static __thread char local_hostname[NI_MAXHOST];
    char address[INET_ADDRSTRLEN];
    const char *resolved, *hostname;
    struct hostent *host;
    size_t hostname_length;

    pthread_once(&resolver_once, resolve_next_symbols);
    if (!next_gethostbyname)
    {
        h_errno = NO_RECOVERY;
        return NULL;
    }
    resolved = local_name(name, address, sizeof(address));
    host = next_gethostbyname(resolved);
    if (!host || resolved == name) return host;

    hostname = configured_hostname();
    hostname_length = strlen(hostname) + 1;
    if (hostname_length > sizeof(local_hostname))
    {
        h_errno = NO_RECOVERY;
        errno = ERANGE;
        return NULL;
    }
    /* Keep libc's hostent and its strings untouched. The address and alias
     * arrays retain the usual gethostbyname lifetime; only h_name differs. */
    local_host = *host;
    memcpy(local_hostname, hostname, hostname_length);
    local_host.h_name = local_hostname;
    return &local_host;
}

int gethostbyname_r(const char *name, struct hostent *ret, char *buf,
                    size_t buflen, struct hostent **result, int *h_errnop)
{
    char address[INET_ADDRSTRLEN];
    const char *resolved, *hostname;
    size_t hostname_length;
    int status;

    pthread_once(&resolver_once, resolve_next_symbols);
    if (!next_gethostbyname_r)
    {
        *result = NULL;
        *h_errnop = NO_RECOVERY;
        return ENOSYS;
    }
    resolved = local_name(name, address, sizeof(address));
    if (resolved == name)
        return next_gethostbyname_r(name, ret, buf, buflen, result, h_errnop);

    /* Reserve the canonical name in the caller's buffer, outside the space
     * libc uses for the numeric address, aliases and pointer arrays. */
    hostname = configured_hostname();
    hostname_length = strlen(hostname) + 1;
    if (buflen < hostname_length)
    {
        *result = NULL;
        *h_errnop = NETDB_INTERNAL;
        errno = ERANGE;
        return ERANGE;
    }
    status = next_gethostbyname_r(resolved, ret, buf, buflen - hostname_length,
                                 result, h_errnop);
    if (status == 0 && *result)
    {
        char *canonical = buf + buflen - hostname_length;
        memcpy(canonical, hostname, hostname_length);
        (*result)->h_name = canonical;
    }
    return status;
}

/*
 * Reports GAMENATIVE_HOSTNAME when set.  Otherwise, Android's hostname is
 * "localhost", which resolves to 127.0.0.1, so report the device's routable
 * address instead.  On any failure the real hostname is kept.
 */
int gethostname(char *name, size_t len)
{
    const char *hostname = configured_hostname();
    char address[INET_ADDRSTRLEN];
    int ret;

    if (hostname && strlen(hostname) + 1 <= len)
    {
        memcpy(name, hostname, strlen(hostname) + 1);
        return 0;
    }

    pthread_once(&resolver_once, resolve_next_symbols);
    if (!next_gethostname)
    {
        errno = ENOSYS;
        return -1;
    }

    ret = next_gethostname(name, len);
    if (ret != 0)
        return ret;
    if (strcmp(name, "localhost") != 0 && name[0] != '\0')
        return 0;

    if (routable_address(address, sizeof(address)) != 0)
        return 0;
    if (strlen(address) + 1 > len)
        return 0;

    memcpy(name, address, strlen(address) + 1);
    DNS_LOG("gethostname -> %s (hostname was localhost)", address);
    return 0;
}
