/* ============================================================================
 * CalDAV Server & HTTP Web Server - Android NDK/POSIX Port of caldavd.c
 * 2026 HUGO - Public Domain
 * ============================================================================ */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <pthread.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include <time.h>
#include <android/log.h>

#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <openssl/pem.h>
#include <openssl/pkcs12.h>
#include <openssl/sha.h>
#include <openssl/rand.h>
#include <openssl/bn.h>

#define SERVER_NAME "CaldAV-D"
#define SERVER_VERSION "2.0-TLS"
#define DEFAULT_PORT 8443
#define DEFAULT_HTTP_PORT 8080
#define DEFAULT_TIMEOUT_MS 2000
#define MAX_CONNECTIONS 20
#define BUFFER_SIZE 8192
#define MAX_PATH 1024

typedef int BOOL;
#define TRUE 1
#define FALSE 0

static char g_BaseDir[MAX_PATH] = {0};
static char CONFIG_FILE[MAX_PATH] = {0};
static char DATA_DIR[MAX_PATH] = {0};
static char WEB_DIR[MAX_PATH] = {0};
static char CA_CERT_FILE[MAX_PATH] = {0};
static char CA_KEY_FILE[MAX_PATH] = {0};
static char LEAF_CERT_FILE[MAX_PATH] = {0};
static char LEAF_KEY_FILE[MAX_PATH] = {0};

static volatile BOOL g_bRunning = FALSE;
static int g_ListenSocket = -1;
static int g_HttpListenSocket = -1;
static int g_Port = DEFAULT_PORT;
static int g_HttpPort = DEFAULT_HTTP_PORT;
static int g_TimeoutMs = DEFAULT_TIMEOUT_MS;
static BOOL g_bAnonymous = TRUE;
static BOOL g_bUseTls = TRUE;

static SSL_CTX* g_SslCtx = NULL;
static pthread_mutex_t g_IniMutex = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    int socket;
    int port;
    BOOL use_tls;
    SSL* ssl;
} ClientThreadCtx;

/* ============================================================================
 * INI File Emulation (Faithful to Win32 Get/WritePrivateProfileString)
 * ============================================================================ */
static void GetPrivateProfileString(const char* section, const char* key, const char* def, char* outBuf, int maxLen, const char* filename) {
    pthread_mutex_lock(&g_IniMutex);
    if (!outBuf || maxLen <= 0) { pthread_mutex_unlock(&g_IniMutex); return; }
    memset(outBuf, 0, maxLen);

    FILE* fp = fopen(filename, "r");
    if (!fp) {
        if (def) strncpy(outBuf, def, maxLen - 1);
        pthread_mutex_unlock(&g_IniMutex);
        return;
    }

    char line[2048];
    char curSec[256] = {0};
    BOOL found = FALSE;
    int keyListOffset = 0;

    while (fgets(line, sizeof(line), fp)) {
        char* p = line;
        while (*p == ' ' || *p == '\t') p++;
        char* nl = strpbrk(p, "\r\n");
        if (nl) *nl = '\0';
        if (*p == ';' || *p == '#' || *p == '\0') continue;

        if (*p == '[') {
            char* end = strchr(p, ']');
            if (end) {
                *end = '\0';
                strncpy(curSec, p + 1, sizeof(curSec) - 1);
            }
            continue;
        }

        if (strcasecmp(curSec, section) == 0) {
            char* eq = strchr(p, '=');
            if (!eq) continue;
            *eq = '\0';
            char* k = p;
            char* v = eq + 1;
            if (key == NULL) {
                int klen = strlen(k);
                if (keyListOffset + klen + 2 < maxLen) {
                    strcpy(outBuf + keyListOffset, k);
                    keyListOffset += klen + 1;
                    outBuf[keyListOffset] = '\0';
                    found = TRUE;
                }
            } else if (strcmp(k, key) == 0) {
                strncpy(outBuf, v, maxLen - 1);
                found = TRUE;
                break;
            }
        }
    }
    fclose(fp);
    if (!found && key != NULL && def != NULL) {
        strncpy(outBuf, def, maxLen - 1);
    }
    pthread_mutex_unlock(&g_IniMutex);
}

static BOOL WritePrivateProfileString(const char* section, const char* key, const char* val, const char* filename) {
    pthread_mutex_lock(&g_IniMutex);
    FILE* fp = fopen(filename, "r");
    char* outContent = calloc(1, 256 * 1024);
    if (!outContent) { if (fp) fclose(fp); pthread_mutex_unlock(&g_IniMutex); return FALSE; }

    char line[2048];
    char curSec[256] = {0};
    BOOL inTargetSec = FALSE;
    BOOL secFound = FALSE;
    BOOL keyWritten = FALSE;

    if (fp) {
        while (fgets(line, sizeof(line), fp)) {
            char rawLine[2048];
            strcpy(rawLine, line);
            char* p = line;
            while (*p == ' ' || *p == '\t') p++;
            char* nl = strpbrk(p, "\r\n");
            if (nl) *nl = '\0';

            if (*p == '[') {
                if (inTargetSec && !keyWritten && key && val) {
                    char kv[1024];
                    snprintf(kv, sizeof(kv), "%s=%s\n", key, val);
                    strcat(outContent, kv);
                    keyWritten = TRUE;
                }
                char* end = strchr(p, ']');
                if (end) {
                    *end = '\0';
                    strncpy(curSec, p + 1, sizeof(curSec) - 1);
                    inTargetSec = (strcasecmp(curSec, section) == 0);
                    if (inTargetSec) secFound = TRUE;
                }
                strcat(outContent, rawLine);
                continue;
            }

            if (inTargetSec && *p != ';' && *p != '#' && *p != '\0') {
                char* eq = strchr(p, '=');
                if (eq) {
                    *eq = '\0';
                    if (key && strcmp(p, key) == 0) {
                        if (val != NULL) {
                            char kv[1024];
                            snprintf(kv, sizeof(kv), "%s=%s\n", key, val);
                            strcat(outContent, kv);
                        }
                        keyWritten = TRUE;
                        continue;
                    }
                }
            }
            strcat(outContent, rawLine);
        }
        fclose(fp);
    }

    if (!secFound && key && val) {
        char secLine[512];
        snprintf(secLine, sizeof(secLine), "[%s]\n%s=%s\n", section, key, val);
        strcat(outContent, secLine);
    } else if (secFound && !keyWritten && key && val) {
        char kv[1024];
        snprintf(kv, sizeof(kv), "%s=%s\n", key, val);
        strcat(outContent, kv);
    }

    fp = fopen(filename, "w");
    if (fp) {
        fputs(outContent, fp);
        fclose(fp);
    }
    free(outContent);
    pthread_mutex_unlock(&g_IniMutex);
    return TRUE;
}

/* ============================================================================
 * Cryptography, Salted SHA-256 & Certificate Generation
 * ============================================================================ */
static void GenerateSaltedHash(const char* password, const char* salt, char* outHex) {
    unsigned char hash[SHA256_DIGEST_LENGTH];
    SHA256_CTX sha256;
    SHA256_Init(&sha256);
    SHA256_Update(&sha256, password, strlen(password));
    SHA256_Update(&sha256, salt, strlen(salt));
    SHA256_Final(hash, &sha256);
    for (int i = 0; i < SHA256_DIGEST_LENGTH; i++) {
        snprintf(outHex + (i * 2), 3, "%02x", hash[i]);
    }
    outHex[64] = '\0';
}

static void Base64Decode(const char* input, char* output, int maxLen) {
    int decoding_table[256];
    const char* b64chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (int i = 0; i < 256; i++) decoding_table[i] = -1;
    for (int i = 0; i < 64; i++) decoding_table[(unsigned char)b64chars[i]] = i;
    int out_idx = 0, val = 0, valb = -8;
    for (const char* c = input; *c; c++) {
        if (decoding_table[(unsigned char)*c] == -1) break;
        val = (val << 6) + decoding_table[(unsigned char)*c];
        valb += 6;
        if (valb >= 0) {
            if (out_idx < maxLen - 1) output[out_idx++] = (char)((val >> valb) & 0xFF);
            valb -= 8;
        }
    }
    output[out_idx] = '\0';
}

static BOOL add_ext(X509 *cert, X509 *issuer, int nid, char *value) {
    X509_EXTENSION *ex;
    X509V3_CTX ctx;
    X509V3_set_ctx_nodb(&ctx);
    X509V3_set_ctx(&ctx, issuer, cert, NULL, NULL, 0);
    ex = X509V3_EXT_conf_nid(NULL, &ctx, nid, value);
    if (!ex) return FALSE;
    X509_add_ext(cert, ex, -1);
    X509_EXTENSION_free(ex);
    return TRUE;
}

static BOOL GenerateCertificates(void) {
    struct stat st;
    if (stat(CA_CERT_FILE, &st) == 0 && stat(LEAF_CERT_FILE, &st) == 0 && stat(LEAF_KEY_FILE, &st) == 0) {
        return TRUE;
    }

    EVP_PKEY *caPkey = EVP_PKEY_new();
    RSA *caRsa = RSA_new();
    BIGNUM *bne = BN_new();
    BN_set_word(bne, RSA_F4);
    RSA_generate_key_ex(caRsa, 2048, bne, NULL);
    EVP_PKEY_assign_RSA(caPkey, caRsa);

    X509 *caCert = X509_new();
    X509_set_version(caCert, 2);
    ASN1_INTEGER_set(X509_get_serialNumber(caCert), 1);
    X509_gmtime_adj(X509_get_notBefore(caCert), 0);
    X509_gmtime_adj(X509_get_notAfter(caCert), 315360000L); // 10 years
    X509_set_pubkey(caCert, caPkey);

    X509_NAME *caName = X509_get_subject_name(caCert);
    X509_NAME_add_entry_by_txt(caName, "CN", MBSTRING_ASC, (unsigned char*)"CalDAV Local Root CA", -1, -1, 0);
    X509_set_issuer_name(caCert, caName);
    add_ext(caCert, caCert, NID_basic_constraints, "critical,CA:TRUE");
    X509_sign(caCert, caPkey, EVP_sha256());

    FILE *fp = fopen(CA_CERT_FILE, "wb");
    if (fp) { PEM_write_X509(fp, caCert); fclose(fp); }
    fp = fopen(CA_KEY_FILE, "wb");
    if (fp) { PEM_write_PrivateKey(fp, caPkey, NULL, NULL, 0, NULL, NULL); fclose(fp); }

    EVP_PKEY *leafPkey = EVP_PKEY_new();
    RSA *leafRsa = RSA_new();
    RSA_generate_key_ex(leafRsa, 2048, bne, NULL);
    BN_free(bne);
    EVP_PKEY_assign_RSA(leafPkey, leafRsa);

    X509 *leafCert = X509_new();
    X509_set_version(leafCert, 2);
    ASN1_INTEGER_set(X509_get_serialNumber(leafCert), (long)time(NULL));
    X509_gmtime_adj(X509_get_notBefore(leafCert), 0);
    X509_gmtime_adj(X509_get_notAfter(leafCert), 315360000L);
    X509_set_pubkey(leafCert, leafPkey);

    X509_NAME *leafName = X509_get_subject_name(leafCert);
    X509_NAME_add_entry_by_txt(leafName, "CN", MBSTRING_ASC, (unsigned char*)"localhost", -1, -1, 0);
    X509_set_issuer_name(leafCert, caName);

    add_ext(leafCert, caCert, NID_subject_alt_name, "DNS:localhost,IP:127.0.0.1,IP:192.168.1.57");
    add_ext(leafCert, caCert, NID_ext_key_usage, "serverAuth");
    X509_sign(leafCert, caPkey, EVP_sha256());

    fp = fopen(LEAF_CERT_FILE, "wb");
    if (fp) { PEM_write_X509(fp, leafCert); fclose(fp); }
    fp = fopen(LEAF_KEY_FILE, "wb");
    if (fp) { PEM_write_PrivateKey(fp, leafPkey, NULL, NULL, 0, NULL, NULL); fclose(fp); }

    X509_free(leafCert);
    EVP_PKEY_free(leafPkey);
    X509_free(caCert);
    EVP_PKEY_free(caPkey);
    return TRUE;
}

static BOOL ExportPfxFile(const char* destPath) {
    if (!GenerateCertificates()) return FALSE;

    FILE *fp = fopen(LEAF_CERT_FILE, "rb");
    if (!fp) return FALSE;
    X509 *leafCert = PEM_read_X509(fp, NULL, NULL, NULL);
    fclose(fp);

    fp = fopen(LEAF_KEY_FILE, "rb");
    if (!fp) { X509_free(leafCert); return FALSE; }
    EVP_PKEY *leafKey = PEM_read_PrivateKey(fp, NULL, NULL, NULL);
    fclose(fp);

    fp = fopen(CA_CERT_FILE, "rb");
    X509 *caCert = fp ? PEM_read_X509(fp, NULL, NULL, NULL) : NULL;
    if (fp) fclose(fp);

    STACK_OF(X509) *caStack = sk_X509_new_null();
    if (caCert) sk_X509_push(caStack, caCert);

    PKCS12 *p12 = PKCS12_create("caldav", "CalDAV Server", leafKey, leafCert, caStack, 0, 0, 0, 0, 0);
    BOOL ok = FALSE;
    if (p12) {
        fp = fopen(destPath, "wb");
        if (fp) {
            i2d_PKCS12_fp(fp, p12);
            fclose(fp);
            ok = TRUE;
        }
        PKCS12_free(p12);
    }

    sk_X509_pop_free(caStack, X509_free);
    X509_free(leafCert);
    EVP_PKEY_free(leafKey);
    return ok;
}

static BOOL InitTLS(void) {
    if (!g_bUseTls) return TRUE;
    if (!GenerateCertificates()) return FALSE;

    SSL_library_init();
    OpenSSL_add_all_algorithms();
    SSL_load_error_strings();

    g_SslCtx = SSL_CTX_new(TLS_server_method());
    if (!g_SslCtx) return FALSE;

    if (SSL_CTX_use_certificate_file(g_SslCtx, LEAF_CERT_FILE, SSL_FILETYPE_PEM) <= 0 ||
        SSL_CTX_use_PrivateKey_file(g_SslCtx, LEAF_KEY_FILE, SSL_FILETYPE_PEM) <= 0) {
        SSL_CTX_free(g_SslCtx);
        g_SslCtx = NULL;
        return FALSE;
    }
    return TRUE;
}

static void CleanupTLS(void) {
    if (g_SslCtx) {
        SSL_CTX_free(g_SslCtx);
        g_SslCtx = NULL;
    }
}

static int TlsRecv(ClientThreadCtx* ctx, char* buf, int max_len) {
    if (!ctx->use_tls) return (int)recv(ctx->socket, buf, max_len, 0);
    if (!ctx->ssl) return -1;
    return SSL_read(ctx->ssl, buf, max_len);
}

static int TlsSend(ClientThreadCtx* ctx, const char* data, int len) {
    if (!ctx->use_tls) return (int)send(ctx->socket, data, len, 0);
    if (!ctx->ssl || len <= 0) return 0;
    int totalSent = 0;
    while (totalSent < len) {
        int r = SSL_write(ctx->ssl, data + totalSent, len - totalSent);
        if (r <= 0) return -1;
        totalSent += r;
    }
    return totalSent;
}

/* ============================================================================
 * Config & User Authentication
 * ============================================================================ */
static void SeedDefaultUser(void) {
    char buf[16] = {0};
    GetPrivateProfileString("users", "admin", "", buf, sizeof(buf), CONFIG_FILE);
    if (buf[0] == '\0') {
        char hash[65];
        GenerateSaltedHash("admin", "salt2026", hash);
        char entry[128];
        snprintf(entry, sizeof(entry), "salt2026,%s", hash);
        WritePrivateProfileString("users", "admin", entry, CONFIG_FILE);
        WritePrivateProfileString("perms", "admin", "1", CONFIG_FILE);
    }
}

static BOOL LoadConfig(void) {
    char buffer[64];
    GetPrivateProfileString("server", "port", "8443", buffer, sizeof(buffer), CONFIG_FILE);
    g_Port = atoi(buffer);
    if (g_Port <= 1000 || g_Port > 65535) g_Port = DEFAULT_PORT;

    GetPrivateProfileString("server", "http_port", "8080", buffer, sizeof(buffer), CONFIG_FILE);
    g_HttpPort = atoi(buffer);
    if (g_HttpPort <= 1000 || g_HttpPort > 65535) g_HttpPort = DEFAULT_HTTP_PORT;

    GetPrivateProfileString("server", "timeout_ms", "2000", buffer, sizeof(buffer), CONFIG_FILE);
    g_TimeoutMs = atoi(buffer);
    if (g_TimeoutMs < 100) g_TimeoutMs = DEFAULT_TIMEOUT_MS;

    GetPrivateProfileString("server", "anonymous", "1", buffer, sizeof(buffer), CONFIG_FILE);
    g_bAnonymous = (atoi(buffer) != 0);

    GetPrivateProfileString("server", "use_tls", "1", buffer, sizeof(buffer), CONFIG_FILE);
    g_bUseTls = (atoi(buffer) != 0);
    return TRUE;
}

static BOOL SaveConfig(void) {
    char buffer[32];
    if (g_Port <= 1000) g_Port = DEFAULT_PORT;
    if (g_HttpPort <= 1000) g_HttpPort = DEFAULT_HTTP_PORT;
    snprintf(buffer, sizeof(buffer), "%d", g_Port); WritePrivateProfileString("server", "port", buffer, CONFIG_FILE);
    snprintf(buffer, sizeof(buffer), "%d", g_HttpPort); WritePrivateProfileString("server", "http_port", buffer, CONFIG_FILE);
    snprintf(buffer, sizeof(buffer), "%d", g_TimeoutMs); WritePrivateProfileString("server", "timeout_ms", buffer, CONFIG_FILE);
    snprintf(buffer, sizeof(buffer), "%d", g_bAnonymous ? 1 : 0); WritePrivateProfileString("server", "anonymous", buffer, CONFIG_FILE);
    snprintf(buffer, sizeof(buffer), "%d", g_bUseTls ? 1 : 0); WritePrivateProfileString("server", "use_tls", buffer, CONFIG_FILE);
    return TRUE;
}

static BOOL AuthenticateRequest(char* buffer, char* outUser) {
    if (outUser) outUser[0] = '\0';
    char* authHeader = strstr(buffer, "Authorization: Basic ");
    if (!authHeader) authHeader = strstr(buffer, "authorization: Basic ");
    if (!authHeader) {
        if (g_bAnonymous) {
            if (outUser) strcpy(outUser, "anonymous");
            return TRUE;
        }
        return FALSE;
    }
    authHeader += 21;
    char b64[256] = {0};
    int i = 0;
    while (authHeader[i] && authHeader[i] != '\r' && authHeader[i] != '\n' && authHeader[i] != ' ' && i < 255) {
        b64[i] = authHeader[i];
        i++;
    }
    b64[i] = '\0';
    char decoded[256] = {0};
    Base64Decode(b64, decoded, 256);
    char* colon = strchr(decoded, ':');
    if (!colon) return FALSE;
    *colon = '\0';
    char* username = decoded;
    char* password = colon + 1;

    char userEntry[256] = {0};
    GetPrivateProfileString("users", username, "", userEntry, sizeof(userEntry), CONFIG_FILE);
    if (strlen(userEntry) == 0) return FALSE;
    char* comma = strchr(userEntry, ',');
    if (!comma) return FALSE;
    *comma = '\0';

    char actualHash[65] = {0};
    GenerateSaltedHash(password, userEntry, actualHash);

    if (strcmp(comma + 1, actualHash) == 0) {
        if (outUser) strcpy(outUser, username);
        return TRUE;
    }
    return FALSE;
}

/* ============================================================================
 * HTTP & CalDAV Protocol Implementation
 * ============================================================================ */
static void GetHTTPMethod(char* buffer, char* outMethod, int maxLen) {
    outMethod[0] = '\0';
    char* space = strchr(buffer, ' ');
    if (!space) return;
    int len = (int)(space - buffer);
    if (len >= maxLen) len = maxLen - 1;
    strncpy(outMethod, buffer, len);
    outMethod[len] = '\0';
}

static void GetRequestPath(char* buffer, char* outPath, int maxLen) {
    outPath[0] = '\0';
    char* space1 = strchr(buffer, ' ');
    if (!space1) {
        strncpy(outPath, "/", maxLen - 1);
        return;
    }
    space1++;
    char* space2 = strchr(space1, ' ');
    int len = space2 ? (int)(space2 - space1) : (int)strlen(space1);
    if (len >= maxLen) len = maxLen - 1;
    strncpy(outPath, space1, len);
    outPath[len] = '\0';
    char* query = strchr(outPath, '?');
    if (query) *query = '\0';
}

static void SendHTTPResponse(ClientThreadCtx* ctx, const char* status, const char* contentType, const char* body, size_t bodyLen, const char* extraHeaders) {
    char header[1024];
    int headerLen = snprintf(header, sizeof(header),
        "HTTP/1.1 %s\r\nContent-Type: %s; charset=utf-8\r\nContent-Length: %lu\r\nConnection: close\r\n"
        "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS, PROPFIND, PROPPATCH, REPORT\r\n"
        "Access-Control-Allow-Headers: Content-Type, Authorization, Depth\r\nServer: %s/%s\r\n%s\r\n",
        status, contentType, (unsigned long)bodyLen, SERVER_NAME, SERVER_VERSION, extraHeaders ? extraHeaders : "");
    TlsSend(ctx, header, headerLen);
    if (bodyLen > 0 && body) TlsSend(ctx, body, (int)bodyLen);
}

static void SendHTTPFileResponse(ClientThreadCtx* ctx, const char* filePath) {
    FILE* fp = fopen(filePath, "rb");
    if (!fp) {
        SendHTTPResponse(ctx, "404 Not Found", "text/plain", "File Not Found", 14, NULL);
        return;
    }
    fseek(fp, 0, SEEK_END);
    long fileSize = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    const char* contentType = "application/octet-stream";
    if (strstr(filePath, ".html")) contentType = "text/html";
    else if (strstr(filePath, ".css")) contentType = "text/css";
    else if (strstr(filePath, ".js")) contentType = "application/javascript";
    else if (strstr(filePath, ".json")) contentType = "application/json";

    char header[512];
    int headerLen = snprintf(header, sizeof(header),
        "HTTP/1.1 200 OK\r\nContent-Type: %s\r\nContent-Length: %ld\r\nConnection: close\r\n"
        "Cache-Control: no-store, no-cache, must-revalidate\r\n\r\n", contentType, fileSize);
    TlsSend(ctx, header, headerLen);

    char fileBuf[4096];
    size_t bytesRead;
    while ((bytesRead = fread(fileBuf, 1, sizeof(fileBuf), fp)) > 0) {
        TlsSend(ctx, fileBuf, (int)bytesRead);
    }
    fclose(fp);
}

static unsigned int CalculateEventETag(const char* title, const char* date, int startMin, int duration, const char* rrule) {
    unsigned int hash = 5381;
    const char* str = title; while (*str) hash = ((hash << 5) + hash) + *str++;
    str = date; while (*str) hash = ((hash << 5) + hash) + *str++;
    str = rrule; if (str) while (*str) hash = ((hash << 5) + hash) + *str++;
    hash = ((hash << 5) + hash) + startMin;
    hash = ((hash << 5) + hash) + duration;
    return hash;
}

static void GetCalendarCTag(char* outCtag, int maxLen) {
    DIR* dir = opendir(DATA_DIR);
    time_t maxTime = 0;
    long maxNsec = 0;
    if (dir) {
        struct dirent* ent;
        while ((ent = readdir(dir)) != NULL) {
            if (strstr(ent->d_name, ".json")) {
                char path[MAX_PATH];
                snprintf(path, sizeof(path), "%s/%s", DATA_DIR, ent->d_name);
                struct stat st;
                if (stat(path, &st) == 0) {
                    if (st.st_mtime > maxTime || (st.st_mtime == maxTime && st.st_mtim.tv_nsec > maxNsec)) {
                        maxTime = st.st_mtime;
                        maxNsec = st.st_mtim.tv_nsec;
                    }
                }
            }
        }
        closedir(dir);
    }
    snprintf(outCtag, maxLen, "%lu-%lu", (unsigned long)maxTime, (unsigned long)maxNsec);
}

static int GetUserPersonIdx(const char* username) {
    if (!username || !username[0]) return 0;
    int idx = 0, curr = 0;
    char filepath[MAX_PATH];
    snprintf(filepath, sizeof(filepath), "%s/people.json", DATA_DIR);
    FILE* fp = fopen(filepath, "rb");
    if (fp) {
        char buf[4096];
        size_t bytes = fread(buf, 1, sizeof(buf) - 1, fp);
        buf[bytes] = '\0';
        char searchStr[256];
        snprintf(searchStr, sizeof(searchStr), "\"%s\"", username);
        char* p = buf;
        while (*p) {
            if (*p == '"') {
                if (strncmp(p, searchStr, strlen(searchStr)) == 0) { idx = curr; break; }
                p++; while (*p && *p != '"') p++;
                curr++;
            }
            if (*p) p++;
        }
        fclose(fp);
    }
    return idx;
}

static void HandleCommandLine(ClientThreadCtx* ctx, char* buffer, const char* authUser) {
    char method[16] = {0};
    char path[MAX_PATH] = {0};
    GetHTTPMethod(buffer, method, sizeof(method));
    GetRequestPath(buffer, path, sizeof(path));

    if (strstr(path, "..")) {
        SendHTTPResponse(ctx, "403 Forbidden", "text/plain", "Forbidden", 9, NULL);
        return;
    }

    char* body = strstr(buffer, "\r\n\r\n");
    if (body) body += 4;

    int contentLen = 0;
    char* clPtr = strstr(buffer, "Content-Length:");
    if (!clPtr) clPtr = strstr(buffer, "Content-length:");
    if (clPtr) contentLen = atoi(clPtr + 15);

    if (strcmp(method, "OPTIONS") == 0) {
        SendHTTPResponse(ctx, "200 OK", "text/plain", "", 0,
            "DAV: 1, 2, 3, calendar-access\r\n"
            "Allow: OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, COPY, MOVE, PROPFIND, PROPPATCH, REPORT\r\n");
    }
    else if (strcmp(method, "PROPFIND") == 0) {
        char xmlResponse[4096];
        if (strncmp(path, "/principal/", 11) == 0) {
            snprintf(xmlResponse, sizeof(xmlResponse),
                "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n"
                "<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n"
                "  <D:response>\n"
                "    <D:href>%s</D:href>\n"
                "    <D:propstat>\n"
                "      <D:prop>\n"
                "        <D:resourcetype><D:principal/></D:resourcetype>\n"
                "        <D:displayname>%s</D:displayname>\n"
                "        <C:calendar-home-set><D:href>/calendar/</D:href></C:calendar-home-set>\n"
                "      </D:prop>\n"
                "      <D:status>HTTP/1.1 200 OK</D:status>\n"
                "    </D:propstat>\n"
                "  </D:response>\n"
                "</D:multistatus>\n",
                path, (authUser && authUser[0]) ? authUser : "anonymous");
        } else {
            char ctag[64] = {0}, calName[128] = "CalDAV-D Server", calColor[32] = "#039BE5";
            GetCalendarCTag(ctag, sizeof(ctag));
            GetPrivateProfileString("calendar_props", "displayname", "CalDAV-D Server", calName, sizeof(calName), CONFIG_FILE);
            GetPrivateProfileString("calendar_props", "color", "#039BE5", calColor, sizeof(calColor), CONFIG_FILE);

            snprintf(xmlResponse, sizeof(xmlResponse),
                "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n"
                "<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\" xmlns:CS=\"http://calendarserver.org/ns/\" xmlns:ICAL=\"http://apple.com/ns/ical/\">\n"
                "  <D:response>\n"
                "    <D:href>%s</D:href>\n"
                "    <D:propstat>\n"
                "      <D:prop>\n"
                "        <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>\n"
                "        <D:displayname>%s</D:displayname>\n"
                "        <ICAL:calendar-color>%s</ICAL:calendar-color>\n"
                "        <CS:getctag>\"%s\"</CS:getctag>\n"
                "        <D:current-user-principal><D:href>/principal/%s/</D:href></D:current-user-principal>\n"
                "        <C:calendar-home-set><D:href>/calendar/</D:href></C:calendar-home-set>\n"
                "      </D:prop>\n"
                "      <D:status>HTTP/1.1 200 OK</D:status>\n"
                "    </D:propstat>\n"
                "  </D:response>\n"
                "</D:multistatus>\n",
                path, calName, calColor, ctag, (authUser && authUser[0]) ? authUser : "anonymous");
        }
        SendHTTPResponse(ctx, "207 Multi-Status", "application/xml; charset=utf-8", xmlResponse, strlen(xmlResponse), NULL);
    }
    else if (strcmp(method, "PROPPATCH") == 0) {
        char color[32] = {0}, name[128] = {0};
        char* pColor = body ? strstr(body, "<calendar-color") : NULL;
        if (!pColor && body) pColor = strstr(body, "<ICAL:calendar-color");
        if (pColor) {
            pColor = strchr(pColor, '>');
            if (pColor) {
                sscanf(pColor + 1, "%31[^<]", color);
                WritePrivateProfileString("calendar_props", "color", color, CONFIG_FILE);
            }
        }
        char* pName = body ? strstr(body, "<D:displayname>") : NULL;
        if (pName) {
            sscanf(pName + 15, "%127[^<]", name);
            WritePrivateProfileString("calendar_props", "displayname", name, CONFIG_FILE);
        }
        char xmlResponse[1024];
        snprintf(xmlResponse, sizeof(xmlResponse),
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n"
            "<D:multistatus xmlns:D=\"DAV:\">\n"
            "  <D:response>\n"
            "    <D:href>%s</D:href>\n"
            "    <D:propstat>\n"
            "      <D:prop><D:displayname/><calendar-color xmlns=\"http://apple.com/ns/ical/\"/></D:prop>\n"
            "      <D:status>HTTP/1.1 200 OK</D:status>\n"
            "    </D:propstat>\n"
            "  </D:response>\n"
            "</D:multistatus>\n", path);
        SendHTTPResponse(ctx, "207 Multi-Status", "application/xml; charset=utf-8", xmlResponse, strlen(xmlResponse), NULL);
    }
    else if (strcmp(method, "REPORT") == 0) {
        char currentCtag[64] = {0};
        GetCalendarCTag(currentCtag, sizeof(currentCtag));
        char currentToken[128];
        snprintf(currentToken, sizeof(currentToken), "http://caldavd/sync/%s", currentCtag);

        char clientToken[128] = {0};
        BOOL isSyncReport = body && strstr(body, "sync-collection") != NULL;
        BOOL isMultiget = body && (strstr(body, ":calendar-multiget") != NULL || strstr(body, "<calendar-multiget") != NULL);

        if (isSyncReport) {
            char* pToken = strstr(body, "sync-token>");
            if (pToken) {
                char* pEnd = strchr(pToken + 11, '<');
                if (pEnd) {
                    int len = (int)(pEnd - (pToken + 11));
                    if (len > 0 && len < 127) {
                        strncpy(clientToken, pToken + 11, len);
                        clientToken[len] = '\0';
                    }
                }
            }
            if (clientToken[0] != '\0') {
                if (strcmp(clientToken, currentToken) == 0) {
                    char xmlResponse[1024];
                    snprintf(xmlResponse, sizeof(xmlResponse),
                        "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n"
                        "<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n"
                        "  <D:sync-token>%s</D:sync-token>\n"
                        "</D:multistatus>\n", currentToken);
                    SendHTTPResponse(ctx, "207 Multi-Status", "application/xml; charset=utf-8", xmlResponse, strlen(xmlResponse), NULL);
                    return;
                } else {
                    const char* errResp =
                        "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n"
                        "<D:error xmlns:D=\"DAV:\">\n"
                        "  <D:valid-sync-token/>\n"
                        "</D:error>\n";
                    SendHTTPResponse(ctx, "403 Forbidden", "application/xml; charset=utf-8", errResp, strlen(errResp), NULL);
                    return;
                }
            }
        }

        char requestedUids[4096] = "|";
        if (isMultiget && body) {
            char* href = body;
            char* hrefTag = strstr(href, "href>");
            while (hrefTag != NULL) {
                href = hrefTag + 5;
                char* endHref = strchr(href, '<');
                if (endHref) {
                    char hrefBuf[256] = {0};
                    int len = (int)(endHref - href);
                    if (len < 255) {
                        strncpy(hrefBuf, href, len);
                        char uid[128] = {0};
                        char* lastSlash = strrchr(hrefBuf, '/');
                        if (lastSlash) sscanf(lastSlash + 1, "%127[^.]", uid);
                        else sscanf(hrefBuf, "%127[^.]", uid);
                        if (uid[0] != '\0' && strlen(requestedUids) + strlen(uid) + 2 < sizeof(requestedUids)) {
                            strcat(requestedUids, uid);
                            strcat(requestedUids, "|");
                        }
                    }
                }
                hrefTag = strstr(href, "href>");
            }
        }

        char* xmlResponse = (char*)malloc(1024 * 1024);
        if (!xmlResponse) {
            SendHTTPResponse(ctx, "500 Internal Error", "text/plain", "Memory Error", 12, NULL);
            return;
        }
        strcpy(xmlResponse, "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");

        DIR* dir = opendir(DATA_DIR);
        if (dir) {
            struct dirent* ent;
            while ((ent = readdir(dir)) != NULL) {
                if (!strstr(ent->d_name, ".json")) continue;
                if (strcmp(ent->d_name, "people.json") == 0 || strcmp(ent->d_name, "contacts.json") == 0) continue;

                char filepath[MAX_PATH];
                snprintf(filepath, sizeof(filepath), "%s/%s", DATA_DIR, ent->d_name);
                FILE* fp = fopen(filepath, "rb");
                if (fp) {
                    fseek(fp, 0, SEEK_END); long size = ftell(fp); fseek(fp, 0, SEEK_SET);
                    char* fileBuf = malloc(size + 1);
                    if (fileBuf) {
                        fread(fileBuf, 1, size, fp); fileBuf[size] = '\0';
                        char* eventStart = fileBuf;
                        while ((eventStart = strstr(eventStart, "{\"id\":")) != NULL) {
                            char id[64] = {0}, title[128] = "Untitled Event", date[16] = {0}, rrule[256] = {0};
                            int startMin = 0, duration = 60;
                            char* pId = strstr(eventStart, "\"id\":\"");
                            if (pId) sscanf(pId + 6, "%63[^\"]", id);

                            if (isMultiget) {
                                char searchUid[130];
                                snprintf(searchUid, sizeof(searchUid), "|%s|", id);
                                if (strstr(requestedUids, searchUid) == NULL) {
                                    eventStart++;
                                    continue;
                                }
                            }
                            char* pTitle = strstr(eventStart, "\"title\":\"");
                            if (pTitle) sscanf(pTitle + 9, "%127[^\"]", title);
                            char* pDate = strstr(eventStart, "\"date\":\"");
                            if (pDate) sscanf(pDate + 8, "%15[^\"]", date);
                            char* pStart = strstr(eventStart, "\"startMin\":");
                            if (pStart) sscanf(pStart + 11, "%d", &startMin);
                            char* pDur = strstr(eventStart, "\"duration\":");
                            if (pDur) sscanf(pDur + 11, "%d", &duration);
                            char* pRrule = strstr(eventStart, "\"rrule\":\"");
                            if (pRrule) sscanf(pRrule + 9, "%255[^\"]", rrule);

                            unsigned int etagHash = CalculateEventETag(title, date, startMin, duration, rrule);
                            char icsDate[9] = {0};
                            if (strlen(date) >= 10) {
                                icsDate[0] = date[0]; icsDate[1] = date[1]; icsDate[2] = date[2]; icsDate[3] = date[3];
                                icsDate[4] = date[5]; icsDate[5] = date[6]; icsDate[6] = date[8]; icsDate[7] = date[9];
                            }
                            int startH = startMin / 60, startM = startMin % 60;
                            int endMin = startMin + duration, endH = endMin / 60, endM = endMin % 60;
                            char rruleBlock[300] = {0};
                            if (rrule[0]) snprintf(rruleBlock, sizeof(rruleBlock), "RRULE:%s\r\n", rrule);

                            char eventBuf[2048];
                            snprintf(eventBuf, sizeof(eventBuf),
                                "  <D:response>\n"
                                "    <D:href>/calendar/%s.ics</D:href>\n"
                                "    <D:propstat>\n"
                                "      <D:prop>\n"
                                "        <D:getetag>\"%s-%x\"</D:getetag>\n"
                                "        <C:calendar-data><![CDATA[BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//CalDAV-D//EN\r\nBEGIN:VEVENT\r\nUID:%s\r\nDTSTAMP:%sT%02d%02d00\r\nDTSTART:%sT%02d%02d00\r\nDTEND:%sT%02d%02d00\r\n%sSUMMARY:%s\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n]]></C:calendar-data>\n"
                                "      </D:prop>\n"
                                "      <D:status>HTTP/1.1 200 OK</D:status>\n"
                                "    </D:propstat>\n"
                                "  </D:response>\n",
                                id, id, etagHash, id, icsDate, startH, startM, icsDate, startH, startM, icsDate, endH, endM, rruleBlock, title);

                            if (strlen(xmlResponse) + strlen(eventBuf) < 1024 * 1024 - 500) {
                                strcat(xmlResponse, eventBuf);
                            }
                            eventStart++;
                        }
                        free(fileBuf);
                    }
                    fclose(fp);
                }
            }
            closedir(dir);
        }

        if (isSyncReport) {
            char tokenBlock[256];
            snprintf(tokenBlock, sizeof(tokenBlock), "  <D:sync-token>%s</D:sync-token>\n", currentToken);
            strcat(xmlResponse, tokenBlock);
        }
        strcat(xmlResponse, "</D:multistatus>\n");
        SendHTTPResponse(ctx, "207 Multi-Status", "application/xml; charset=utf-8", xmlResponse, strlen(xmlResponse), NULL);
        free(xmlResponse);
    }
    else if (strcmp(method, "GET") == 0) {
        if (strcmp(path, "/api/me") == 0) {
            char canEdit[8] = "0";
            GetPrivateProfileString("perms", authUser, "0", canEdit, sizeof(canEdit), CONFIG_FILE);
            char visible[1024] = "";
            char prefKey[256]; snprintf(prefKey, sizeof(prefKey), "%s_visible", authUser);
            GetPrivateProfileString("prefs", prefKey, "", visible, sizeof(visible), CONFIG_FILE);

            char json[2048];
            snprintf(json, sizeof(json), "{\"user\":\"%s\", \"edit_all\":%s, \"visible_people\":\"%s\"}",
                authUser, canEdit[0] == '1' ? "true" : "false", visible);
            SendHTTPResponse(ctx, "200 OK", "application/json", json, strlen(json), NULL);
        }
        else if (strcmp(path, "/api/contacts") == 0) {
            char filepath[MAX_PATH];
            snprintf(filepath, sizeof(filepath), "%s/contacts.json", DATA_DIR);
            FILE* fp = fopen(filepath, "rb");
            if (fp) {
                fseek(fp, 0, SEEK_END); long size = ftell(fp); fseek(fp, 0, SEEK_SET);
                char* fileBuf = malloc(size + 1);
                if (fileBuf) {
                    fread(fileBuf, 1, size, fp); fileBuf[size] = '\0';
                    SendHTTPResponse(ctx, "200 OK", "application/json", fileBuf, size, NULL);
                    free(fileBuf);
                }
                fclose(fp);
            } else {
                SendHTTPResponse(ctx, "200 OK", "application/json", "[]", 2, NULL);
            }
        }
        else if (strcmp(path, "/api/setting") == 0) {
            char key[128] = {0};
            char* keyPtr = strstr(buffer, "key=");
            if (keyPtr) {
                keyPtr += 4;
                char* end = strchr(keyPtr, ' ');
                if (!end) end = strchr(keyPtr, '&');
                if (!end) end = strchr(keyPtr, '\r');
                if (end && (end - keyPtr) < 127) strncpy(key, keyPtr, end - keyPtr);
                else strncpy(key, keyPtr, 127);
            }
            char val[1024] = "";
            GetPrivateProfileString("settings", key, "", val, sizeof(val), CONFIG_FILE);
            SendHTTPResponse(ctx, "200 OK", "text/plain", val, strlen(val), NULL);
        }
        else if (strcmp(path, "/calendar/all") == 0) {
            char* responseBody = (char*)malloc(1024 * 1024);
            if (responseBody) {
                strcpy(responseBody, "[");
                DIR* dir = opendir(DATA_DIR);
                int first = 1;
                if (dir) {
                    struct dirent* ent;
                    while ((ent = readdir(dir)) != NULL) {
                        if (!strstr(ent->d_name, ".json")) continue;
                        if (strcmp(ent->d_name, "people.json") == 0 || strcmp(ent->d_name, "contacts.json") == 0) continue;
                        char filepath[MAX_PATH];
                        snprintf(filepath, sizeof(filepath), "%s/%s", DATA_DIR, ent->d_name);
                        FILE* fp = fopen(filepath, "rb");
                        if (fp) {
                            fseek(fp, 0, SEEK_END); long size = ftell(fp); fseek(fp, 0, SEEK_SET);
                            if (size > 0 && strlen(responseBody) + size + 2 < 1024 * 1024) {
                                if (!first) strcat(responseBody, ",");
                                size_t curLen = strlen(responseBody);
                                fread(responseBody + curLen, 1, size, fp);
                                responseBody[curLen + size] = '\0';
                                first = 0;
                            }
                            fclose(fp);
                        }
                    }
                    closedir(dir);
                }
                strcat(responseBody, "]");
                SendHTTPResponse(ctx, "200 OK", "application/json", responseBody, strlen(responseBody), NULL);
                free(responseBody);
            } else {
                SendHTTPResponse(ctx, "500 Internal Error", "text/plain", "Memory Error", 12, NULL);
            }
        }
        else if (strncmp(path, "/calendar/", 10) == 0) {
            char filepath[MAX_PATH];
            snprintf(filepath, sizeof(filepath), "%s/%s", DATA_DIR, path + 10);
            FILE* fp = fopen(filepath, "rb");
            if (fp) {
                fseek(fp, 0, SEEK_END); long size = ftell(fp); fseek(fp, 0, SEEK_SET);
                char* fileBuf = malloc(size + 1);
                if (fileBuf) {
                    fread(fileBuf, 1, size, fp); fileBuf[size] = '\0';
                    SendHTTPResponse(ctx, "200 OK", "application/json", fileBuf, size, NULL);
                    free(fileBuf);
                }
                fclose(fp);
            } else {
                SendHTTPResponse(ctx, "404 Not Found", "text/plain", "Not Found", 9, NULL);
            }
        }
        else {
            SendHTTPResponse(ctx, "404 Not Found", "text/plain", "Endpoint not found", 18, NULL);
        }
    }
    else if (strcmp(method, "PUT") == 0) {
        if (strcmp(path, "/api/contacts") == 0) {
            if (body && contentLen > 0) {
                char filepath[MAX_PATH];
                snprintf(filepath, sizeof(filepath), "%s/contacts.json", DATA_DIR);
                FILE* fp = fopen(filepath, "wb");
                if (fp) { fwrite(body, 1, contentLen, fp); fclose(fp); }
            }
            SendHTTPResponse(ctx, "200 OK", "text/plain", "Saved", 5, NULL);
        }
        else if (strcmp(path, "/api/user") == 0) {
            if (body && contentLen > 0) {
                char* safeBody = malloc(contentLen + 1);
                memcpy(safeBody, body, contentLen);
                safeBody[contentLen] = '\0';
                char oldName[128] = {0}, newName[128] = {0};
                char* pipe = strchr(safeBody, '|');
                if (pipe) {
                    *pipe = '\0';
                    strncpy(oldName, safeBody, 127);
                    strncpy(newName, pipe + 1, 127);
                } else {
                    strncpy(newName, safeBody, 127);
                }
                free(safeBody);

                if (newName[0]) {
                    char existingUser[256] = {0};
                    if (oldName[0]) GetPrivateProfileString("users", oldName, "", existingUser, sizeof(existingUser), CONFIG_FILE);
                    if (existingUser[0]) {
                        WritePrivateProfileString("users", newName, existingUser, CONFIG_FILE);
                        if (strcmp(oldName, newName) != 0) {
                            WritePrivateProfileString("users", oldName, NULL, CONFIG_FILE);
                            char perms[8] = "0";
                            GetPrivateProfileString("perms", oldName, "0", perms, sizeof(perms), CONFIG_FILE);
                            WritePrivateProfileString("perms", newName, perms, CONFIG_FILE);
                            WritePrivateProfileString("perms", oldName, NULL, CONFIG_FILE);
                        }
                    } else {
                        char hash[65], entry[128];
                        GenerateSaltedHash("password123", "salt2026", hash);
                        snprintf(entry, sizeof(entry), "salt2026,%s", hash);
                        WritePrivateProfileString("users", newName, entry, CONFIG_FILE);
                        WritePrivateProfileString("perms", newName, "0", CONFIG_FILE);
                    }
                }
            }
            SendHTTPResponse(ctx, "200 OK", "text/plain", "Saved", 5, NULL);
        }
        else if (strcmp(path, "/api/prefs") == 0) {
            char prefKey[256]; snprintf(prefKey, sizeof(prefKey), "%s_visible", authUser);
            if (body && contentLen > 0) {
                char* safeBody = malloc(contentLen + 1);
                memcpy(safeBody, body, contentLen);
                safeBody[contentLen] = '\0';
                WritePrivateProfileString("prefs", prefKey, safeBody, CONFIG_FILE);
                free(safeBody);
            } else {
                WritePrivateProfileString("prefs", prefKey, "", CONFIG_FILE);
            }
            SendHTTPResponse(ctx, "200 OK", "text/plain", "Saved", 5, NULL);
        }
        else if (strcmp(path, "/api/setting") == 0) {
            if (body && contentLen > 0) {
                char* safeBody = malloc(contentLen + 1);
                memcpy(safeBody, body, contentLen);
                safeBody[contentLen] = '\0';
                char* eq = strchr(safeBody, '=');
                if (eq) {
                    *eq = '\0';
                    WritePrivateProfileString("settings", safeBody, eq + 1, CONFIG_FILE);
                }
                free(safeBody);
            }
            SendHTTPResponse(ctx, "200 OK", "text/plain", "Saved", 5, NULL);
        }
        else if (strncmp(path, "/calendar/", 10) == 0) {
            if (strstr(path, ".ics") != NULL) {
                if (!body || contentLen <= 0) { SendHTTPResponse(ctx, "400 Bad Request", "text/plain", "No Body", 7, NULL); return; }
                char uid[128] = {0}, summary[256] = "New Event", dtstart[64] = {0}, dtend[64] = {0}, rrule[256] = {0};
                sscanf(path + 10, "%127[^.]", uid);
                char* p = strstr(body, "SUMMARY:");
                if (p) sscanf(p + 8, "%255[^\r\n]", summary);
                p = strstr(body, "DTSTART");
                if (p) {
                    char *colon = strchr(p, ':'), *newline = strchr(p, '\n');
                    if (colon && (!newline || colon < newline)) sscanf(colon + 1, "%63[^\r\n]", dtstart);
                }
                p = strstr(body, "DTEND");
                if (p) {
                    char *colon = strchr(p, ':'), *newline = strchr(p, '\n');
                    if (colon && (!newline || colon < newline)) sscanf(colon + 1, "%63[^\r\n]", dtend);
                }
                char* pRruleIn = strstr(body, "RRULE:");
                if (pRruleIn) sscanf(pRruleIn + 6, "%255[^\r\n]", rrule);

                int y = 2026, m = 10, d = 26, h = 12, min = 0;
                if (strlen(dtstart) >= 15) sscanf(dtstart, "%4d%2d%2dT%2d%2d", &y, &m, &d, &h, &min);
                else if (strlen(dtstart) >= 8) sscanf(dtstart, "%4d%2d%2d", &y, &m, &d);
                int startMin = h * 60 + min;

                int ey = y, em = m, ed = d, eh = h + 1, emin = min;
                if (strlen(dtend) >= 15) sscanf(dtend, "%4d%2d%2dT%2d%2d", &ey, &em, &ed, &eh, &emin);
                else if (strlen(dtend) >= 8) sscanf(dtend, "%4d%2d%2d", &ey, &em, &ed);
                int duration = (eh * 60 + emin) - startMin;
                if (duration <= 0) duration = 60;

                char dateStr[16]; snprintf(dateStr, sizeof(dateStr), "%04d/%02d/%02d", y, m, d);
                int quarter = ((m - 1) / 3) + 1;
                char filepath[MAX_PATH]; snprintf(filepath, sizeof(filepath), "%s/%04d-Q%d.json", DATA_DIR, y, quarter);

                int assignedIdx = GetUserPersonIdx(authUser);
                unsigned int etagHash = CalculateEventETag(summary, dateStr, startMin, duration, rrule);

                char newJson[1024];
                snprintf(newJson, sizeof(newJson), "{\"id\":\"%s\",\"title\":\"%s\",\"startMin\":%d,\"duration\":%d,\"color\":\"#039BE5\",\"date\":\"%s\",\"personIdx\":%d%s%s%s}",
                    uid, summary, startMin, duration, dateStr, assignedIdx,
                    rrule[0] ? ",\"rrule\":\"" : "", rrule[0] ? rrule : "", rrule[0] ? "\"" : "");

                FILE* fp = fopen(filepath, "rb");
                char* fileBuf = NULL; long size = 0;
                if (fp) {
                    fseek(fp, 0, SEEK_END); size = ftell(fp); fseek(fp, 0, SEEK_SET);
                    fileBuf = malloc(size + 1); if (fileBuf) { fread(fileBuf, 1, size, fp); fileBuf[size] = '\0'; }
                    fclose(fp);
                }

                char* outBuf = malloc((size > 0 ? size : 10) + 2048);
                strcpy(outBuf, "[");
                int first = 1;
                if (fileBuf) {
                    char* eventStart = fileBuf;
                    while ((eventStart = strstr(eventStart, "{\"id\":")) != NULL) {
                        char currentId[128] = {0};
                        char* pId = strstr(eventStart, "\"id\":\"");
                        if (pId) sscanf(pId + 6, "%63[^\"]", currentId);
                        char* nextObj = strstr(eventStart + 1, "{\"id\":");
                        if (strcmp(currentId, uid) != 0) {
                            if (!first) strcat(outBuf, ",");
                            size_t copyLen = nextObj ? (size_t)(nextObj - eventStart) : strlen(eventStart);
                            while (copyLen > 0 && (eventStart[copyLen - 1] == ']' || eventStart[copyLen - 1] == ',' || eventStart[copyLen - 1] == ' ' || eventStart[copyLen - 1] == '\r' || eventStart[copyLen - 1] == '\n')) copyLen--;
                            if (copyLen > 0) {
                                size_t currLen = strlen(outBuf);
                                memcpy(outBuf + currLen, eventStart, copyLen);
                                outBuf[currLen + copyLen] = '\0';
                            }
                            first = 0;
                        }
                        if (!nextObj) break;
                        eventStart = nextObj;
                    }
                    free(fileBuf);
                }
                if (!first) strcat(outBuf, ",");
                strcat(outBuf, newJson);
                strcat(outBuf, "]");

                fp = fopen(filepath, "wb");
                if (fp) { fwrite(outBuf, 1, strlen(outBuf), fp); fclose(fp); }
                free(outBuf);

                char header[256];
                snprintf(header, sizeof(header), "ETag: \"%s-%x\"\r\n", uid, etagHash);
                SendHTTPResponse(ctx, "201 Created", "text/plain", "", 0, header);
            } else {
                char filepath[MAX_PATH];
                snprintf(filepath, sizeof(filepath), "%s/%s", DATA_DIR, path + 10);
                if (body) {
                    FILE* fp = fopen(filepath, "wb");
                    if (fp) {
                        int bodyRead = (int)strlen(body);
                        fwrite(body, 1, bodyRead, fp);
                        int remaining = contentLen - bodyRead;
                        while (remaining > 0) {
                            char chunk[4096];
                            int r = TlsRecv(ctx, chunk, sizeof(chunk));
                            if (r <= 0) break;
                            fwrite(chunk, 1, r, fp);
                            remaining -= r;
                        }
                        fclose(fp);
                        SendHTTPResponse(ctx, "201 Created", "text/plain", "Created", 7, NULL);
                    } else {
                        SendHTTPResponse(ctx, "500 Internal Error", "text/plain", "I/O Error", 9, NULL);
                    }
                } else {
                    SendHTTPResponse(ctx, "400 Bad Request", "text/plain", "No Body", 7, NULL);
                }
            }
        }
    }
    else if (strcmp(method, "DELETE") == 0 && strncmp(path, "/calendar/", 10) == 0) {
        if (strstr(path, ".ics") != NULL) {
            char uid[128] = {0};
            sscanf(path + 10, "%127[^.]", uid);
            DIR* dir = opendir(DATA_DIR);
            if (dir) {
                struct dirent* ent;
                while ((ent = readdir(dir)) != NULL) {
                    if (!strstr(ent->d_name, ".json")) continue;
                    if (strcmp(ent->d_name, "people.json") == 0 || strcmp(ent->d_name, "contacts.json") == 0) continue;
                    char filepath[MAX_PATH];
                    snprintf(filepath, sizeof(filepath), "%s/%s", DATA_DIR, ent->d_name);
                    FILE* fp = fopen(filepath, "rb");
                    if (fp) {
                        fseek(fp, 0, SEEK_END); long size = ftell(fp); fseek(fp, 0, SEEK_SET);
                        char* fileBuf = malloc(size + 1);
                        if (fileBuf) {
                            fread(fileBuf, 1, size, fp); fileBuf[size] = '\0';
                            char* outBuf = malloc(size + 1024);
                            strcpy(outBuf, "[");
                            int first = 1, modified = 0;
                            char* eventStart = fileBuf;
                            while ((eventStart = strstr(eventStart, "{\"id\":")) != NULL) {
                                char currentId[128] = {0};
                                char* pId = strstr(eventStart, "\"id\":\"");
                                if (pId) sscanf(pId + 6, "%63[^\"]", currentId);
                                char* nextObj = strstr(eventStart + 1, "{\"id\":");
                                if (strcmp(currentId, uid) != 0) {
                                    if (!first) strcat(outBuf, ",");
                                    size_t copyLen = nextObj ? (size_t)(nextObj - eventStart) : strlen(eventStart);
                                    while (copyLen > 0 && (eventStart[copyLen - 1] == ']' || eventStart[copyLen - 1] == ',' || eventStart[copyLen - 1] == ' ' || eventStart[copyLen - 1] == '\r' || eventStart[copyLen - 1] == '\n')) copyLen--;
                                    if (copyLen > 0) {
                                        size_t currLen = strlen(outBuf);
                                        memcpy(outBuf + currLen, eventStart, copyLen);
                                        outBuf[currLen + copyLen] = '\0';
                                    }
                                    first = 0;
                                } else {
                                    modified = 1;
                                }
                                if (!nextObj) break;
                                eventStart = nextObj;
                            }
                            strcat(outBuf, "]");
                            fclose(fp);
                            if (modified) {
                                fp = fopen(filepath, "wb");
                                if (fp) { fwrite(outBuf, 1, strlen(outBuf), fp); fclose(fp); }
                            }
                            free(outBuf);
                            free(fileBuf);
                        } else {
                            fclose(fp);
                        }
                    }
                }
                closedir(dir);
            }
            SendHTTPResponse(ctx, "204 No Content", "text/plain", "", 0, NULL);
        } else {
            char filepath[MAX_PATH];
            snprintf(filepath, sizeof(filepath), "%s/%s", DATA_DIR, path + 10);
            remove(filepath);
            SendHTTPResponse(ctx, "204 No Content", "text/plain", "", 0, NULL);
        }
    }
}

/* ============================================================================
 * Socket & Thread Handlers
 * ============================================================================ */
static void* ClientHandlerThread(void* pParam) {
    ClientThreadCtx* ctx = (ClientThreadCtx*)pParam;
    if (!ctx) return NULL;

    struct timeval tv;
    tv.tv_sec = g_TimeoutMs / 1000;
    tv.tv_usec = (g_TimeoutMs % 1000) * 1000;
    setsockopt(ctx->socket, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof(tv));
    setsockopt(ctx->socket, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof(tv));

    if (ctx->use_tls && g_SslCtx) {
        ctx->ssl = SSL_new(g_SslCtx);
        SSL_set_fd(ctx->ssl, ctx->socket);
        if (SSL_accept(ctx->ssl) <= 0) {
            goto cleanup;
        }
    }

    char buffer[BUFFER_SIZE];
    memset(buffer, 0, sizeof(buffer));
    int bytesReceived = TlsRecv(ctx, buffer, sizeof(buffer) - 1);
    if (bytesReceived > 0) {
        buffer[bytesReceived] = '\0';
        char method[16] = {0};
        char path[MAX_PATH] = {0};
        GetHTTPMethod(buffer, method, sizeof(method));
        GetRequestPath(buffer, path, sizeof(path));

        if (method[0] != '\0') {
            if (ctx->port == g_HttpPort && strcmp(method, "GET") == 0 &&
                strncmp(path, "/calendar/", 10) != 0 && strncmp(path, "/api/", 5) != 0 &&
                (strcmp(path, "/") == 0 || strstr(path, ".html") || strstr(path, ".css") || strstr(path, ".js") || strstr(path, ".json"))) {
                if (strcmp(path, "/") == 0) strncpy(path, "/calendar.html", sizeof(path) - 1);
                char fullPath[MAX_PATH];
                snprintf(fullPath, sizeof(fullPath), "%s%s", WEB_DIR, path);
                if (strstr(path, "..") == NULL) SendHTTPFileResponse(ctx, fullPath);
                else SendHTTPResponse(ctx, "403 Forbidden", "text/plain", "Forbidden", 9, NULL);
            } else {
                char authUser[256] = {0};
                if (strcmp(method, "OPTIONS") == 0 || AuthenticateRequest(buffer, authUser)) {
                    HandleCommandLine(ctx, buffer, authUser);
                } else {
                    SendHTTPResponse(ctx, "401 Unauthorized", "text/plain", "Unauthorized", 12, "WWW-Authenticate: x-Basic realm=\"CalDAV Server\"\r\n");
                }
            }
        }
    }

cleanup:
    if (ctx->ssl) {
        SSL_shutdown(ctx->ssl);
        SSL_free(ctx->ssl);
    }
    shutdown(ctx->socket, SHUT_RDWR);
    close(ctx->socket);
    free(ctx);
    return NULL;
}

static int CreateListenSocket(int port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return -1;
    int opt = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);
    if (bind(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0 || listen(sock, MAX_CONNECTIONS) < 0) {
        close(sock);
        return -1;
    }
    return sock;
}

static void* AcceptLoopThread(void* pParam) {
    int isHttp = (int)(intptr_t)pParam;
    while (g_bRunning) {
        int listenSock = isHttp ? g_HttpListenSocket : g_ListenSocket;
        if (listenSock < 0) break;
        struct sockaddr_in clientAddr;
        socklen_t clientLen = sizeof(clientAddr);
        int clientSock = accept(listenSock, (struct sockaddr*)&clientAddr, &clientLen);
        if (clientSock < 0) continue;

        ClientThreadCtx* ctx = calloc(1, sizeof(ClientThreadCtx));
        if (ctx) {
            ctx->socket = clientSock;
            ctx->port = isHttp ? g_HttpPort : g_Port;
            ctx->use_tls = g_bUseTls;
            pthread_t tid;
            if (pthread_create(&tid, NULL, ClientHandlerThread, ctx) == 0) {
                pthread_detach(tid);
            } else {
                close(clientSock);
                free(ctx);
            }
        } else {
            close(clientSock);
        }
    }
    return NULL;
}

/* ============================================================================
 * JNI Bridge for Android UI & Service
 * ============================================================================ */
JNIEXPORT void JNICALL Java_com_hugo_caldavd_CaldavService_nativeInit(JNIEnv* env, jclass clazz, jstring baseDirStr) {
    const char* baseDir = (*env)->GetStringUTFChars(env, baseDirStr, NULL);
    strncpy(g_BaseDir, baseDir, sizeof(g_BaseDir) - 1);
    (*env)->ReleaseStringUTFChars(env, baseDirStr, baseDir);

    snprintf(CONFIG_FILE, sizeof(CONFIG_FILE), "%s/caldavd.ini", g_BaseDir);
    snprintf(DATA_DIR, sizeof(DATA_DIR), "%s/calendars", g_BaseDir);
    snprintf(WEB_DIR, sizeof(WEB_DIR), "%s/web", g_BaseDir);
    snprintf(CA_CERT_FILE, sizeof(CA_CERT_FILE), "%s/CalDAV_RootCA.crt", g_BaseDir);
    snprintf(CA_KEY_FILE, sizeof(CA_KEY_FILE), "%s/CalDAV_RootCA.key", g_BaseDir);
    snprintf(LEAF_CERT_FILE, sizeof(LEAF_CERT_FILE), "%s/CalDAV_Leaf.crt", g_BaseDir);
    snprintf(LEAF_KEY_FILE, sizeof(LEAF_KEY_FILE), "%s/CalDAV_Leaf.key", g_BaseDir);

    mkdir(DATA_DIR, 0777);
    mkdir(WEB_DIR, 0777);
    LoadConfig();
    SaveConfig();
    SeedDefaultUser();
}

JNIEXPORT jboolean JNICALL Java_com_hugo_caldavd_CaldavService_nativeStartServer(JNIEnv* env, jclass clazz) {
    if (g_bRunning) return JNI_TRUE;
    LoadConfig();
    if (!InitTLS()) return JNI_FALSE;

    g_ListenSocket = CreateListenSocket(g_Port);
    if (g_ListenSocket < 0) {
        CleanupTLS();
        return JNI_FALSE;
    }
    g_HttpListenSocket = CreateListenSocket(g_HttpPort);

    g_bRunning = TRUE;
    pthread_t t1, t2;
    pthread_create(&t1, NULL, AcceptLoopThread, (void*)(intptr_t)0);
    pthread_detach(t1);
    if (g_HttpListenSocket >= 0) {
        pthread_create(&t2, NULL, AcceptLoopThread, (void*)(intptr_t)1);
        pthread_detach(t2);
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_hugo_caldavd_CaldavService_nativeStopServer(JNIEnv* env, jclass clazz) {
    g_bRunning = FALSE;
    if (g_ListenSocket >= 0) { shutdown(g_ListenSocket, SHUT_RDWR); close(g_ListenSocket); g_ListenSocket = -1; }
    if (g_HttpListenSocket >= 0) { shutdown(g_HttpListenSocket, SHUT_RDWR); close(g_HttpListenSocket); g_HttpListenSocket = -1; }
    CleanupTLS();
}

JNIEXPORT jboolean JNICALL Java_com_hugo_caldavd_CaldavService_nativeIsRunning(JNIEnv* env, jclass clazz) {
    return g_bRunning ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL Java_com_hugo_caldavd_CaldavService_nativeGetConfig(JNIEnv* env, jclass clazz) {
    LoadConfig();
    jint vals[5] = { g_Port, g_HttpPort, g_TimeoutMs, g_bAnonymous ? 1 : 0, g_bUseTls ? 1 : 0 };
    jintArray arr = (*env)->NewIntArray(env, 5);
    (*env)->SetIntArrayRegion(env, arr, 0, 5, vals);
    return arr;
}

JNIEXPORT void JNICALL Java_com_hugo_caldavd_CaldavService_nativeSaveConfig(JNIEnv* env, jclass clazz, jint port, jint httpPort, jint timeoutMs, jboolean anon, jboolean tls) {
    if (port > 1000 && port <= 65535) g_Port = port;
    if (httpPort > 1000 && httpPort <= 65535) g_HttpPort = httpPort;
    if (timeoutMs > 0) g_TimeoutMs = timeoutMs;
    g_bAnonymous = anon ? TRUE : FALSE;
    g_bUseTls = tls ? TRUE : FALSE;
    SaveConfig();
}
JNIEXPORT jobjectArray JNICALL Java_com_hugo_caldavd_CaldavService_nativeGetUsers(JNIEnv* env, jclass clazz) {
    char keys[4096] = {0};
    GetPrivateProfileString("users", NULL, "", keys, sizeof(keys), CONFIG_FILE);
    int count = 0;
    char* p = keys;
    while (*p) { count++; p += strlen(p) + 1; }

    jobjectArray ret = (*env)->NewObjectArray(env, count, (*env)->FindClass(env, "java/lang/String"), NULL);
    p = keys;
    for (int i = 0; i < count; i++) {
        (*env)->SetObjectArrayElement(env, ret, i, (*env)->NewStringUTF(env, p));
        p += strlen(p) + 1;
    }
    return ret;
}

JNIEXPORT jboolean JNICALL Java_com_hugo_caldavd_CaldavService_nativeGetUserCanEditAll(JNIEnv* env, jclass clazz, jstring userStr) {
    const char* user = (*env)->GetStringUTFChars(env, userStr, NULL);
    char canEdit[8] = "0";
    GetPrivateProfileString("perms", user, "0", canEdit, sizeof(canEdit), CONFIG_FILE);
    (*env)->ReleaseStringUTFChars(env, userStr, user);
    return canEdit[0] == '1' ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_hugo_caldavd_CaldavService_nativeSaveUser(JNIEnv* env, jclass clazz, jstring userStr, jstring passStr, jboolean canEditAll) {
    const char* user = (*env)->GetStringUTFChars(env, userStr, NULL);
    const char* pass = (*env)->GetStringUTFChars(env, passStr, NULL);
    if (user[0] && pass[0]) {
        char hash[65], entry[128];
        GenerateSaltedHash(pass, "salt2026", hash);
        snprintf(entry, sizeof(entry), "salt2026,%s", hash);
        WritePrivateProfileString("users", user, entry, CONFIG_FILE);
        WritePrivateProfileString("perms", user, canEditAll ? "1" : "0", CONFIG_FILE);
    }
    (*env)->ReleaseStringUTFChars(env, userStr, user);
    (*env)->ReleaseStringUTFChars(env, passStr, pass);
}

JNIEXPORT void JNICALL Java_com_hugo_caldavd_CaldavService_nativeDeleteUser(JNIEnv* env, jclass clazz, jstring userStr) {
    const char* user = (*env)->GetStringUTFChars(env, userStr, NULL);
    WritePrivateProfileString("users", user, NULL, CONFIG_FILE);
    WritePrivateProfileString("perms", user, NULL, CONFIG_FILE);
    (*env)->ReleaseStringUTFChars(env, userStr, user);
}

JNIEXPORT jstring JNICALL Java_com_hugo_caldavd_CaldavService_nativeResetPassword(JNIEnv* env, jclass clazz, jstring userStr) {
    const char* user = (*env)->GetStringUTFChars(env, userStr, NULL);
    srand((unsigned int)time(NULL));
    char newPass[16];
    snprintf(newPass, sizeof(newPass), "%04x%04x", rand() % 0xFFFF, rand() % 0xFFFF);
    char hash[65], entry[128];
    GenerateSaltedHash(newPass, "salt2026", hash);
    snprintf(entry, sizeof(entry), "salt2026,%s", hash);
    WritePrivateProfileString("users", user, entry, CONFIG_FILE);
    (*env)->ReleaseStringUTFChars(env, userStr, user);
    return (*env)->NewStringUTF(env, newPass);
}

JNIEXPORT jstring JNICALL Java_com_hugo_caldavd_CaldavService_nativeGenerateAndGetRootCaPath(JNIEnv* env, jclass clazz) {
    if (GenerateCertificates()) {
        return (*env)->NewStringUTF(env, CA_CERT_FILE);
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_com_hugo_caldavd_CaldavService_nativeExportPfx(JNIEnv* env, jclass clazz, jstring destPathStr) {
    const char* destPath = (*env)->GetStringUTFChars(env, destPathStr, NULL);
    BOOL ok = ExportPfxFile(destPath);
    (*env)->ReleaseStringUTFChars(env, destPathStr, destPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_hugo_caldavd_CaldavService_nativeImportIcs(JNIEnv* env, jclass clazz, jstring icsContentStr) {
    const char* icsBuf = (*env)->GetStringUTFChars(env, icsContentStr, NULL);
    const char* eventStart = icsBuf;
    int importedCount = 0;

    while ((eventStart = strstr(eventStart, "BEGIN:VEVENT")) != NULL) {
        char uid[128] = {0}, summary[256] = "Imported Event", dtstart[32] = {0}, dtend[32] = {0};
        const char* p = strstr(eventStart, "UID:");
        if (p) sscanf(p + 4, "%127[^\r\n]", uid);
        else snprintf(uid, sizeof(uid), "imp_%lu_%d", (unsigned long)time(NULL), importedCount);

        p = strstr(eventStart, "SUMMARY:");
        if (p) sscanf(p + 8, "%255[^\r\n]", summary);

        p = strstr(eventStart, "DTSTART");
        if (p) { p = strchr(p, ':'); if (p) sscanf(p + 1, "%31[^\r\n]", dtstart); }

        p = strstr(eventStart, "DTEND");
        if (p) { p = strchr(p, ':'); if (p) sscanf(p + 1, "%31[^\r\n]", dtend); }

        int y = 2026, m = 1, d = 1, h = 12, min = 0;
        if (strlen(dtstart) >= 8) sscanf(dtstart, "%4d%2d%2dT%2d%2d", &y, &m, &d, &h, &min);
        int startMins = h * 60 + min;

        int ey = y, em = m, ed = d, eh = h + 1, emin = min;
        if (strlen(dtend) >= 8) sscanf(dtend, "%4d%2d%2dT%2d%2d", &ey, &em, &ed, &eh, &emin);
        int duration = (eh * 60 + emin) - startMins;
        if (duration <= 0) duration = 60;

        int quarter = ((m - 1) / 3) + 1;
        char qFile[MAX_PATH]; snprintf(qFile, sizeof(qFile), "%s/%04d-Q%d.json", DATA_DIR, y, quarter);

        char newJson[1024];
        snprintf(newJson, sizeof(newJson), "{\"id\":\"%s\",\"title\":\"%s\",\"startMin\":%d,\"duration\":%d,\"color\":\"#33B679\",\"date\":\"%04d/%02d/%02d\",\"personIdx\":0}", uid, summary, startMins, duration, y, m, d);

        FILE* fp = fopen(qFile, "rb");
        char* fileBuf = NULL; long fsize = 0;
        if (fp) {
            fseek(fp, 0, SEEK_END); fsize = ftell(fp); fseek(fp, 0, SEEK_SET);
            fileBuf = malloc(fsize + 1); if (fileBuf) { fread(fileBuf, 1, fsize, fp); fileBuf[fsize] = '\0'; }
            fclose(fp);
        }

        char* outBuf = malloc((fsize > 0 ? fsize : 10) + 2048);
        strcpy(outBuf, "[");
        int first = 1;
        if (fileBuf) {
            char* eStart = fileBuf;
            while ((eStart = strstr(eStart, "{\"id\":")) != NULL) {
                char currentId[128] = {0};
                char* pId = strstr(eStart, "\"id\":\"");
                if (pId) sscanf(pId + 6, "%63[^\"]", currentId);
                char* nextObj = strstr(eStart + 1, "{\"id\":");
                if (strcmp(currentId, uid) != 0) {
                    if (!first) strcat(outBuf, ",");
                    int copyLen = nextObj ? (int)(nextObj - eStart) : (int)strlen(eStart);
                    while (copyLen > 0 && (eStart[copyLen - 1] == ']' || eStart[copyLen - 1] == ',' || eStart[copyLen - 1] == ' ' || eStart[copyLen - 1] == '\r' || eStart[copyLen - 1] == '\n')) copyLen--;
                    strncat(outBuf, eStart, copyLen);
                    first = 0;
                }
                if (!nextObj) break;
                eStart = nextObj;
            }
            free(fileBuf);
        }
        if (!first) strcat(outBuf, ",");
        strcat(outBuf, newJson);
        strcat(outBuf, "]");

        fp = fopen(qFile, "wb");
        if (fp) { fwrite(outBuf, 1, strlen(outBuf), fp); fclose(fp); }
        free(outBuf);

        importedCount++;
        eventStart++;
    }
    (*env)->ReleaseStringUTFChars(env, icsContentStr, icsBuf);
    return importedCount;
}

JNIEXPORT jstring JNICALL Java_com_hugo_caldavd_CaldavService_nativeExportIcs(JNIEnv* env, jclass clazz) {
    char* outIcs = calloc(1, 1024 * 1024);
    if (!outIcs) return (*env)->NewStringUTF(env, "");
    strcpy(outIcs, "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//CalDAV-D//EN\r\n");

    DIR* dir = opendir(DATA_DIR);
    if (dir) {
        struct dirent* ent;
        while ((ent = readdir(dir)) != NULL) {
            if (!strstr(ent->d_name, ".json") || strcmp(ent->d_name, "people.json") == 0 || strcmp(ent->d_name, "contacts.json") == 0) continue;
            char filepath[MAX_PATH];
            snprintf(filepath, sizeof(filepath), "%s/%s", DATA_DIR, ent->d_name);
            FILE* fp = fopen(filepath, "rb");
            if (fp) {
                fseek(fp, 0, SEEK_END); long size = ftell(fp); fseek(fp, 0, SEEK_SET);
                char* fileBuf = malloc(size + 1);
                if (fileBuf) {
                    fread(fileBuf, 1, size, fp); fileBuf[size] = '\0';
                    char* eventStart = fileBuf;
                    while ((eventStart = strstr(eventStart, "{\"id\":")) != NULL) {
                        char id[64] = {0}, title[128] = "Untitled Event", date[16] = {0};
                        int startMin = 0, duration = 60;
                        char* pId = strstr(eventStart, "\"id\":\"");
                        if (pId) sscanf(pId + 6, "%63[^\"]", id);
                        char* pTitle = strstr(eventStart, "\"title\":\"");
                        if (pTitle) sscanf(pTitle + 9, "%127[^\"]", title);
                        char* pDate = strstr(eventStart, "\"date\":\"");
                        if (pDate) sscanf(pDate + 8, "%15[^\"]", date);
                        char* pStart = strstr(eventStart, "\"startMin\":");
                        if (pStart) sscanf(pStart + 11, "%d", &startMin);
                        char* pDur = strstr(eventStart, "\"duration\":");
                        if (pDur) sscanf(pDur + 11, "%d", &duration);

                        char icsDate[9] = {0};
                        if (strlen(date) >= 10) {
                            icsDate[0] = date[0]; icsDate[1] = date[1]; icsDate[2] = date[2]; icsDate[3] = date[3];
                            icsDate[4] = date[5]; icsDate[5] = date[6]; icsDate[6] = date[8]; icsDate[7] = date[9];
                        }
                        int startH = startMin / 60, startM = startMin % 60;
                        int endMin = startMin + duration, endH = endMin / 60, endM = endMin % 60;

                        char eventBuf[1024];
                        snprintf(eventBuf, sizeof(eventBuf),
                            "BEGIN:VEVENT\r\nUID:%s\r\nDTSTAMP:%sT%02d%02d00\r\nDTSTART:%sT%02d%02d00\r\nDTEND:%sT%02d%02d00\r\nSUMMARY:%s\r\nEND:VEVENT\r\n",
                            id, icsDate, startH, startM, icsDate, startH, startM, icsDate, endH, endM, title);
                        if (strlen(outIcs) + strlen(eventBuf) < 1024 * 1024 - 100) strcat(outIcs, eventBuf);
                        eventStart++;
                    }
                    free(fileBuf);
                }
                fclose(fp);
            }
        }
        closedir(dir);
    }
    strcat(outIcs, "END:VCALENDAR\r\n");
    jstring res = (*env)->NewStringUTF(env, outIcs);
    free(outIcs);
    return res;
}
