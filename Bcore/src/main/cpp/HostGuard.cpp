#include "HostGuard.h"
#include "Utils/HostGuardRawSyscall.h"
#include "Utils/HostGuardXorString.h"

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <zlib.h>

#if BLACKBOX_GUARD_DIAGNOSTIC_ENABLED
#include <android/log.h>
#endif

#ifndef BLACKBOX_GUARD_SIGNATURE_SHA256
#define BLACKBOX_GUARD_SIGNATURE_SHA256 ""
#endif

#ifndef BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS
#define BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS 3
#endif

#ifndef BLACKBOX_GUARD_DIAGNOSTIC_ENABLED
#define BLACKBOX_GUARD_DIAGNOSTIC_ENABLED 0
#endif

namespace bbguard {

constexpr size_t kSmallFileMax = 16 * 1024;
constexpr size_t kMapsFileMax = 1024 * 1024;
constexpr size_t kPathMax = 768;
constexpr size_t kCertNameMax = 512;
constexpr size_t kMaxCertSize = 2 * 1024 * 1024;
constexpr uint32_t kEocdSignature = 0x06054b50u;
constexpr uint32_t kCentralSignature = 0x02014b50u;
constexpr uint32_t kLocalSignature = 0x04034b50u;

struct Buffer {
    uint8_t *data;
    size_t capacity;
    size_t size;
};

struct ZlibAllocHeader {
    size_t capacity;
};

#if BLACKBOX_GUARD_DIAGNOSTIC_ENABLED
static void guardLog(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, "BBGuard", "%s", message);
}
#define GUARD_LOG_LITERAL(message) guardLog(BB_GUARD_STR(message).c_str())
#define GUARD_LOG_VALUE(message) guardLog(message)
#else
#define GUARD_LOG_LITERAL(message) do { } while (false)
#define GUARD_LOG_VALUE(message) do { } while (false)
#endif

static size_t bb_strlen(const char *value) {
    if (value == nullptr) {
        return 0;
    }
    size_t length = 0;
    while (value[length] != '\0') {
        ++length;
    }
    return length;
}

static void bb_memset(void *dst, int value, size_t size) {
    uint8_t *out = static_cast<uint8_t *>(dst);
    for (size_t i = 0; i < size; ++i) {
        out[i] = static_cast<uint8_t>(value);
    }
}

static bool asciiEqualIgnoreCase(char left, char right) {
    if (left >= 'A' && left <= 'Z') {
        left = static_cast<char>(left + ('a' - 'A'));
    }
    if (right >= 'A' && right <= 'Z') {
        right = static_cast<char>(right + ('a' - 'A'));
    }
    return left == right;
}

static bool startsWith(const char *value, size_t valueLength, const char *prefix) {
    size_t prefixLength = bb_strlen(prefix);
    if (valueLength < prefixLength) {
        return false;
    }
    for (size_t i = 0; i < prefixLength; ++i) {
        if (!asciiEqualIgnoreCase(value[i], prefix[i])) {
            return false;
        }
    }
    return true;
}

static bool endsWith(const char *value, size_t valueLength, const char *suffix) {
    size_t suffixLength = bb_strlen(suffix);
    if (valueLength < suffixLength) {
        return false;
    }
    size_t offset = valueLength - suffixLength;
    for (size_t i = 0; i < suffixLength; ++i) {
        if (!asciiEqualIgnoreCase(value[offset + i], suffix[i])) {
            return false;
        }
    }
    return true;
}

static bool contains(const char *value, const char *needle) {
    size_t valueLength = bb_strlen(value);
    size_t needleLength = bb_strlen(needle);
    if (needleLength == 0 || valueLength < needleLength) {
        return needleLength == 0;
    }
    for (size_t i = 0; i + needleLength <= valueLength; ++i) {
        bool match = true;
        for (size_t j = 0; j < needleLength; ++j) {
            if (value[i + j] != needle[j]) {
                match = false;
                break;
            }
        }
        if (match) {
            return true;
        }
    }
    return false;
}

static bool copyString(char *dst, size_t capacity, const char *src, size_t length) {
    if (capacity == 0 || length >= capacity) {
        return false;
    }
    for (size_t i = 0; i < length; ++i) {
        dst[i] = src[i];
    }
    dst[length] = '\0';
    return true;
}

static bool appendString(char *dst, size_t capacity, const char *suffix) {
    size_t dstLength = bb_strlen(dst);
    size_t suffixLength = bb_strlen(suffix);
    if (dstLength + suffixLength >= capacity) {
        return false;
    }
    for (size_t i = 0; i < suffixLength; ++i) {
        dst[dstLength + i] = suffix[i];
    }
    dst[dstLength + suffixLength] = '\0';
    return true;
}

static const char *findSubstring(const char *value, const char *needle) {
    size_t valueLength = bb_strlen(value);
    size_t needleLength = bb_strlen(needle);
    if (needleLength == 0 || valueLength < needleLength) {
        return needleLength == 0 ? value : nullptr;
    }
    for (size_t i = 0; i + needleLength <= valueLength; ++i) {
        bool match = true;
        for (size_t j = 0; j < needleLength; ++j) {
            if (value[i + j] != needle[j]) {
                match = false;
                break;
            }
        }
        if (match) {
            return value + i;
        }
    }
    return nullptr;
}

static uint16_t readLe16(const uint8_t *data) {
    return static_cast<uint16_t>(data[0] | (data[1] << 8));
}

static uint32_t readLe32(const uint8_t *data) {
    return static_cast<uint32_t>(data[0])
           | (static_cast<uint32_t>(data[1]) << 8)
           | (static_cast<uint32_t>(data[2]) << 16)
           | (static_cast<uint32_t>(data[3]) << 24);
}

static uint64_t readLe64(const uint8_t *data) {
    return static_cast<uint64_t>(readLe32(data))
           | (static_cast<uint64_t>(readLe32(data + 4)) << 32);
}

static bool equalsString(const char *left, const char *right) {
    size_t leftLength = bb_strlen(left);
    if (leftLength != bb_strlen(right)) {
        return false;
    }
    for (size_t i = 0; i < leftLength; ++i) {
        if (left[i] != right[i]) {
            return false;
        }
    }
    return true;
}

static Buffer allocBuffer(size_t capacity) {
    Buffer buffer = {nullptr, 0, 0};
    if (capacity == 0) {
        return buffer;
    }
    void *memory = rawMmap(capacity);
    if (memory == nullptr) {
        return buffer;
    }
    buffer.data = static_cast<uint8_t *>(memory);
    buffer.capacity = capacity;
    buffer.size = 0;
    return buffer;
}

static voidpf zlibAlloc(voidpf, uInt items, uInt size) {
    if (items == 0 || size == 0) {
        return nullptr;
    }
    constexpr size_t kSizeMax = static_cast<size_t>(-1);
    size_t itemCount = static_cast<size_t>(items);
    size_t itemSize = static_cast<size_t>(size);
    if (itemSize > (kSizeMax - sizeof(ZlibAllocHeader)) / itemCount) {
        return nullptr;
    }
    size_t payload = itemCount * itemSize;
    size_t capacity = sizeof(ZlibAllocHeader) + payload;
    auto *header = static_cast<ZlibAllocHeader *>(rawMmap(capacity));
    if (header == nullptr) {
        return nullptr;
    }
    header->capacity = capacity;
    return reinterpret_cast<uint8_t *>(header) + sizeof(ZlibAllocHeader);
}

static void zlibFree(voidpf, voidpf address) {
    if (address == nullptr) {
        return;
    }
    auto *header = reinterpret_cast<ZlibAllocHeader *>(
            reinterpret_cast<uint8_t *>(address) - sizeof(ZlibAllocHeader));
    rawMunmap(header, header->capacity);
}

static void freeBuffer(Buffer *buffer) {
    if (buffer != nullptr && buffer->data != nullptr) {
        rawMunmap(buffer->data, buffer->capacity);
        buffer->data = nullptr;
        buffer->capacity = 0;
        buffer->size = 0;
    }
}

static bool readFile(const char *path, Buffer *buffer, size_t maxSize) {
    if (path == nullptr || buffer == nullptr || maxSize == 0) {
        return false;
    }
    *buffer = allocBuffer(maxSize + 1);
    if (buffer->data == nullptr) {
        return false;
    }
    long fd = rawOpenAt(path, 0);
    if (rawIsError(fd)) {
        freeBuffer(buffer);
        return false;
    }
    size_t total = 0;
    while (total < maxSize) {
        long count = rawRead(static_cast<int>(fd), buffer->data + total, maxSize - total);
        if (count == 0) {
            break;
        }
        if (rawIsError(count) || count < 0) {
            rawClose(static_cast<int>(fd));
            freeBuffer(buffer);
            return false;
        }
        total += static_cast<size_t>(count);
    }
    rawClose(static_cast<int>(fd));
    buffer->data[total] = 0;
    buffer->size = total;
    return total > 0;
}

static bool readAt(int fd, long offset, void *dst, size_t size) {
    if (rawIsError(rawLseek(fd, offset, 0))) {
        return false;
    }
    uint8_t *out = static_cast<uint8_t *>(dst);
    size_t total = 0;
    while (total < size) {
        long count = rawRead(fd, out + total, size - total);
        if (count <= 0 || rawIsError(count)) {
            return false;
        }
        total += static_cast<size_t>(count);
    }
    return true;
}

static long fileSize(int fd) {
    long end = rawLseek(fd, 0, 2);
    if (rawIsError(end) || end <= 0) {
        return -1;
    }
    return end;
}

static bool readCmdlineFile(const char *path, char *out, size_t capacity) {
    Buffer file = {nullptr, 0, 0};
    if (!readFile(path, &file, kSmallFileMax)) {
        return false;
    }
    size_t length = 0;
    while (length < file.size && file.data[length] != 0) {
        ++length;
    }
    bool ok = copyString(out, capacity, reinterpret_cast<const char *>(file.data), length);
    freeBuffer(&file);
    return ok;
}

static bool readProcSelfCmdline(char *out, size_t capacity) {
    auto path = BB_GUARD_STR("/proc/self/cmdline");
    return readCmdlineFile(path.c_str(), out, capacity);
}

static bool appendPositiveDecimal(char *dst, size_t capacity, long value) {
    if (value <= 0) {
        return false;
    }
    char digits[32];
    size_t count = 0;
    while (value > 0 && count < sizeof(digits)) {
        digits[count++] = static_cast<char>('0' + (value % 10));
        value /= 10;
    }
    size_t dstLength = bb_strlen(dst);
    if (dstLength + count >= capacity) {
        return false;
    }
    for (size_t i = 0; i < count; ++i) {
        dst[dstLength + i] = digits[count - i - 1];
    }
    dst[dstLength + count] = '\0';
    return true;
}

static bool readProcPidCmdline(char *out, size_t capacity) {
    long pid = rawGetPid();
    if (pid <= 0) {
        return false;
    }
    auto prefix = BB_GUARD_STR("/proc/");
    auto suffix = BB_GUARD_STR("/cmdline");
    char path[64];
    path[0] = '\0';
    if (!appendString(path, sizeof(path), prefix.c_str())
        || !appendPositiveDecimal(path, sizeof(path), pid)
        || !appendString(path, sizeof(path), suffix.c_str())) {
        return false;
    }
    return readCmdlineFile(path, out, capacity);
}

static bool readLibcProgramInvocationName(char *out, size_t capacity) {
#if defined(__BIONIC__)
    const char *programName = getprogname();
    if (programName == nullptr || programName[0] == '\0') {
        return false;
    }
    return copyString(out, capacity, programName, bb_strlen(programName));
#else
    (void) out;
    (void) capacity;
    return false;
#endif
}

static bool readProcessName(char *out, size_t capacity) {
    return readProcSelfCmdline(out, capacity)
           || readProcPidCmdline(out, capacity)
           || readLibcProgramInvocationName(out, capacity);
}

static void stripProcessSuffix(char *processName) {
    if (processName == nullptr) {
        return;
    }
    for (size_t i = 0; processName[i] != '\0'; ++i) {
        if (processName[i] == ':') {
            processName[i] = '\0';
            return;
        }
    }
}

static bool extractPathFromMapsLine(const char *line, const char *lineEnd,
                                    char *path, size_t pathCapacity) {
    const char *cursor = line;
    while (cursor < lineEnd && *cursor != '/') {
        ++cursor;
    }
    if (cursor == lineEnd) {
        return false;
    }
    return copyString(path, pathCapacity, cursor, static_cast<size_t>(lineEnd - cursor));
}

static bool normalizeApkPath(const char *path, char *out, size_t capacity) {
    auto apkSuffix = BB_GUARD_STR(".apk");
    size_t pathLength = bb_strlen(path);
    size_t suffixLength = bb_strlen(apkSuffix.c_str());
    for (size_t i = 0; i + suffixLength <= pathLength; ++i) {
        bool match = true;
        for (size_t j = 0; j < suffixLength; ++j) {
            if (!asciiEqualIgnoreCase(path[i + j], apkSuffix.c_str()[j])) {
                match = false;
                break;
            }
        }
        if (match) {
            return copyString(out, capacity, path, i + suffixLength);
        }
    }
    return false;
}

static bool findCurrentApkPath(char *out, size_t capacity) {
    char processName[256];
    if (!readProcessName(processName, sizeof(processName))) {
        return false;
    }
    stripProcessSuffix(processName);

    auto mapsPath = BB_GUARD_STR("/proc/self/maps");
    Buffer maps = {nullptr, 0, 0};
    if (!readFile(mapsPath.c_str(), &maps, kMapsFileMax)) {
        return false;
    }

    auto baseApk = BB_GUARD_STR("/base.apk");
    auto nativeLibDir = BB_GUARD_STR("/lib/");
    auto selfSo = BB_GUARD_STR("libblackbox.so");
    char fallback[kPathMax];
    fallback[0] = '\0';

    const char *data = reinterpret_cast<const char *>(maps.data);
    const char *end = data + maps.size;
    for (const char *line = data; line < end;) {
        const char *lineEnd = line;
        while (lineEnd < end && *lineEnd != '\n') {
            ++lineEnd;
        }
        char path[kPathMax];
        if (!extractPathFromMapsLine(line, lineEnd, path, sizeof(path))) {
            line = lineEnd < end ? lineEnd + 1 : end;
            continue;
        }
        if (contains(path, selfSo.c_str()) && contains(path, processName)) {
            const char *libDir = findSubstring(path, nativeLibDir.c_str());
            if (libDir != nullptr) {
                size_t prefixLength = static_cast<size_t>(libDir - path);
                if (copyString(fallback, sizeof(fallback), path, prefixLength)
                    && appendString(fallback, sizeof(fallback), baseApk.c_str())) {
                    bool ok = copyString(out, capacity, fallback, bb_strlen(fallback));
                    freeBuffer(&maps);
                    return ok;
                }
            }
        }
        char apkPath[kPathMax];
        if (normalizeApkPath(path, apkPath, sizeof(apkPath))) {
            if (fallback[0] == '\0' && endsWith(apkPath, bb_strlen(apkPath), baseApk.c_str())) {
                copyString(fallback, sizeof(fallback), apkPath, bb_strlen(apkPath));
            }
            if (contains(apkPath, processName)
                && endsWith(apkPath, bb_strlen(apkPath), baseApk.c_str())) {
                bool ok = copyString(out, capacity, apkPath, bb_strlen(apkPath));
                freeBuffer(&maps);
                return ok;
            }
        }
        line = lineEnd < end ? lineEnd + 1 : end;
    }

    bool ok = fallback[0] != '\0' && copyString(out, capacity, fallback, bb_strlen(fallback));
    freeBuffer(&maps);
    return ok;
}

struct Sha256Context {
    uint8_t data[64];
    uint32_t datalen;
    uint64_t bitlen;
    uint32_t state[8];
};

static uint32_t rotr(uint32_t value, uint32_t bits) {
    return (value >> bits) | (value << (32u - bits));
}

static void sha256Transform(Sha256Context *ctx, const uint8_t data[64]) {
    static constexpr uint32_t k[64] = {
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
            0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
            0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
            0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
            0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
            0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
            0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
            0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
            0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
    };

    uint32_t m[64];
    for (uint32_t i = 0, j = 0; i < 16; ++i, j += 4) {
        m[i] = (static_cast<uint32_t>(data[j]) << 24)
               | (static_cast<uint32_t>(data[j + 1]) << 16)
               | (static_cast<uint32_t>(data[j + 2]) << 8)
               | static_cast<uint32_t>(data[j + 3]);
    }
    for (uint32_t i = 16; i < 64; ++i) {
        uint32_t s0 = rotr(m[i - 15], 7) ^ rotr(m[i - 15], 18) ^ (m[i - 15] >> 3);
        uint32_t s1 = rotr(m[i - 2], 17) ^ rotr(m[i - 2], 19) ^ (m[i - 2] >> 10);
        m[i] = m[i - 16] + s0 + m[i - 7] + s1;
    }

    uint32_t a = ctx->state[0];
    uint32_t b = ctx->state[1];
    uint32_t c = ctx->state[2];
    uint32_t d = ctx->state[3];
    uint32_t e = ctx->state[4];
    uint32_t f = ctx->state[5];
    uint32_t g = ctx->state[6];
    uint32_t h = ctx->state[7];

    for (uint32_t i = 0; i < 64; ++i) {
        uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + s1 + ch + k[i] + m[i];
        uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = s0 + maj;
        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

static void sha256Init(Sha256Context *ctx) {
    ctx->datalen = 0;
    ctx->bitlen = 0;
    ctx->state[0] = 0x6a09e667u;
    ctx->state[1] = 0xbb67ae85u;
    ctx->state[2] = 0x3c6ef372u;
    ctx->state[3] = 0xa54ff53au;
    ctx->state[4] = 0x510e527fu;
    ctx->state[5] = 0x9b05688cu;
    ctx->state[6] = 0x1f83d9abu;
    ctx->state[7] = 0x5be0cd19u;
}

static void sha256Update(Sha256Context *ctx, const uint8_t *data, size_t len) {
    for (size_t i = 0; i < len; ++i) {
        ctx->data[ctx->datalen] = data[i];
        ctx->datalen++;
        if (ctx->datalen == 64) {
            sha256Transform(ctx, ctx->data);
            ctx->bitlen += 512;
            ctx->datalen = 0;
        }
    }
}

static void sha256Final(Sha256Context *ctx, uint8_t hash[32]) {
    uint32_t i = ctx->datalen;
    if (ctx->datalen < 56) {
        ctx->data[i++] = 0x80;
        while (i < 56) {
            ctx->data[i++] = 0x00;
        }
    } else {
        ctx->data[i++] = 0x80;
        while (i < 64) {
            ctx->data[i++] = 0x00;
        }
        sha256Transform(ctx, ctx->data);
        bb_memset(ctx->data, 0, 56);
    }

    ctx->bitlen += ctx->datalen * 8;
    ctx->data[63] = static_cast<uint8_t>(ctx->bitlen);
    ctx->data[62] = static_cast<uint8_t>(ctx->bitlen >> 8);
    ctx->data[61] = static_cast<uint8_t>(ctx->bitlen >> 16);
    ctx->data[60] = static_cast<uint8_t>(ctx->bitlen >> 24);
    ctx->data[59] = static_cast<uint8_t>(ctx->bitlen >> 32);
    ctx->data[58] = static_cast<uint8_t>(ctx->bitlen >> 40);
    ctx->data[57] = static_cast<uint8_t>(ctx->bitlen >> 48);
    ctx->data[56] = static_cast<uint8_t>(ctx->bitlen >> 56);
    sha256Transform(ctx, ctx->data);

    for (i = 0; i < 4; ++i) {
        for (uint32_t j = 0; j < 8; ++j) {
            hash[i + (j * 4)] = static_cast<uint8_t>((ctx->state[j] >> (24 - i * 8)) & 0xff);
        }
    }
}

static char hexChar(uint8_t value) {
    return static_cast<char>(value < 10 ? '0' + value : 'a' + (value - 10));
}

static void toHex(const uint8_t digest[32], char out[65]) {
    for (size_t i = 0; i < 32; ++i) {
        out[i * 2] = hexChar(static_cast<uint8_t>(digest[i] >> 4));
        out[i * 2 + 1] = hexChar(static_cast<uint8_t>(digest[i] & 0x0f));
    }
    out[64] = '\0';
}

static bool isTokenSeparator(char c) {
    return c == ',' || c == ';' || c == ' ' || c == '\t' || c == '\n' || c == '\r';
}

static bool allowedDigestMatches(const uint8_t digest[32]) {
    auto expectedString = BB_GUARD_STR(BLACKBOX_GUARD_SIGNATURE_SHA256);
    const char *expected = expectedString.c_str();
    if (expected == nullptr || expected[0] == '\0') {
        return true;
    }

    char actual[65];
    toHex(digest, actual);
    const char *cursor = expected;
    while (*cursor != '\0') {
        while (isTokenSeparator(*cursor)) {
            ++cursor;
        }
        if (*cursor == '\0') {
            break;
        }
        const char *token = cursor;
        size_t length = 0;
        while (cursor[length] != '\0' && !isTokenSeparator(cursor[length])) {
            ++length;
        }
        if (length == 64) {
            bool match = true;
            for (size_t i = 0; i < 64; ++i) {
                if (!asciiEqualIgnoreCase(token[i], actual[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        cursor += length;
    }
    return false;
}

static bool bytesDigestMatchesAllowed(const uint8_t *data, size_t size) {
    Sha256Context sha;
    uint8_t digest[32];
    sha256Init(&sha);
    sha256Update(&sha, data, size);
    sha256Final(&sha, digest);
    return allowedDigestMatches(digest);
}

static bool readAsn1Length(const uint8_t *data, size_t size, size_t *cursor, size_t *length) {
    if (*cursor >= size) {
        return false;
    }
    uint8_t first = data[(*cursor)++];
    if ((first & 0x80u) == 0) {
        *length = first;
        return *cursor + *length <= size;
    }
    size_t count = first & 0x7fu;
    if (count == 0 || count > sizeof(size_t) || *cursor + count > size) {
        return false;
    }
    size_t value = 0;
    for (size_t i = 0; i < count; ++i) {
        value = (value << 8) | data[(*cursor)++];
    }
    *length = value;
    return *cursor + *length <= size;
}

static bool readAsn1Tlv(const uint8_t *data, size_t size, size_t *cursor, uint8_t expectedTag,
                        size_t *valueOffset, size_t *valueLength, size_t *totalOffset,
                        size_t *totalLength) {
    size_t start = *cursor;
    if (*cursor >= size || data[(*cursor)++] != expectedTag) {
        return false;
    }
    size_t length = 0;
    if (!readAsn1Length(data, size, cursor, &length)) {
        return false;
    }
    *valueOffset = *cursor;
    *valueLength = length;
    *totalOffset = start;
    *totalLength = (*cursor + length) - start;
    *cursor += length;
    return true;
}

static bool embeddedX509DigestMatchesAllowed(const uint8_t *data, size_t size) {
    for (size_t offset = 0; offset + 8 < size; ++offset) {
        size_t cursor = offset;
        size_t certValue = 0;
        size_t certLength = 0;
        size_t certOffset = 0;
        size_t certTotal = 0;
        if (!readAsn1Tlv(data, size, &cursor, 0x30, &certValue, &certLength,
                         &certOffset, &certTotal)) {
            continue;
        }

        size_t innerCursor = certValue;
        size_t value = 0;
        size_t length = 0;
        size_t totalOffset = 0;
        size_t totalLength = 0;
        if (!readAsn1Tlv(data, certValue + certLength, &innerCursor, 0x30, &value,
                         &length, &totalOffset, &totalLength)) {
            continue;
        }
        if (!readAsn1Tlv(data, certValue + certLength, &innerCursor, 0x30, &value,
                         &length, &totalOffset, &totalLength)) {
            continue;
        }
        if (!readAsn1Tlv(data, certValue + certLength, &innerCursor, 0x03, &value,
                         &length, &totalOffset, &totalLength)) {
            continue;
        }
        if (innerCursor == certValue + certLength && certTotal > 256
            && bytesDigestMatchesAllowed(data + certOffset, certTotal)) {
            return true;
        }
    }
    return false;
}

static bool certificateBytesMatchAllowed(const uint8_t *data, size_t size) {
    return bytesDigestMatchesAllowed(data, size)
           || embeddedX509DigestMatchesAllowed(data, size);
}

static bool isSignatureConfigured() {
    auto expectedString = BB_GUARD_STR(BLACKBOX_GUARD_SIGNATURE_SHA256);
    return expectedString.c_str()[0] != '\0';
}

static bool isCertificateEntryName(const char *name, size_t nameLength) {
    auto metaInf = BB_GUARD_STR("META-INF/");
    auto rsa = BB_GUARD_STR(".RSA");
    auto dsa = BB_GUARD_STR(".DSA");
    auto ec = BB_GUARD_STR(".EC");
    return startsWith(name, nameLength, metaInf.c_str())
           && (endsWith(name, nameLength, rsa.c_str())
               || endsWith(name, nameLength, dsa.c_str())
               || endsWith(name, nameLength, ec.c_str()));
}

static bool inflateRawDeflate(const uint8_t *input, size_t inputSize,
                              uint8_t *output, size_t outputSize,
                              size_t *actualOutputSize) {
    z_stream stream;
    bb_memset(&stream, 0, sizeof(stream));
    stream.zalloc = zlibAlloc;
    stream.zfree = zlibFree;
    stream.next_in = const_cast<Bytef *>(reinterpret_cast<const Bytef *>(input));
    stream.avail_in = static_cast<uInt>(inputSize);
    stream.next_out = reinterpret_cast<Bytef *>(output);
    stream.avail_out = static_cast<uInt>(outputSize);
    if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) {
        return false;
    }
    int result = inflate(&stream, Z_FINISH);
    bool ok = result == Z_STREAM_END;
    if (actualOutputSize != nullptr) {
        *actualOutputSize = static_cast<size_t>(stream.total_out);
    }
    inflateEnd(&stream);
    return ok;
}

static bool readAndMatchCertificateEntry(int fd, uint16_t method, uint32_t compressedSize,
                                         uint32_t uncompressedSize, uint32_t localOffset) {
    if (compressedSize == 0 || compressedSize > kMaxCertSize || uncompressedSize > kMaxCertSize) {
        return false;
    }
    uint8_t localHeader[30];
    if (!readAt(fd, static_cast<long>(localOffset), localHeader, sizeof(localHeader))
        || readLe32(localHeader) != kLocalSignature) {
        return false;
    }
    uint16_t localNameLength = readLe16(localHeader + 26);
    uint16_t localExtraLength = readLe16(localHeader + 28);
    long dataOffset = static_cast<long>(localOffset) + 30L + localNameLength + localExtraLength;

    Buffer compressed = allocBuffer(compressedSize);
    if (compressed.data == nullptr) {
        return false;
    }
    bool ok = readAt(fd, dataOffset, compressed.data, compressedSize);
    if (!ok) {
        freeBuffer(&compressed);
        return false;
    }

    bool matched = false;
    if (method == 0) {
        matched = certificateBytesMatchAllowed(compressed.data, compressedSize);
    } else if (method == 8) {
        if (uncompressedSize == 0) {
            freeBuffer(&compressed);
            return false;
        }
        Buffer output = allocBuffer(uncompressedSize);
        if (output.data == nullptr) {
            freeBuffer(&compressed);
            return false;
        }
        size_t actual = 0;
        ok = inflateRawDeflate(compressed.data, compressedSize, output.data, uncompressedSize, &actual);
        matched = ok && certificateBytesMatchAllowed(output.data, actual);
        freeBuffer(&output);
        if (!ok) {
            freeBuffer(&compressed);
            return false;
        }
    } else {
        freeBuffer(&compressed);
        return false;
    }
    freeBuffer(&compressed);
    return matched;
}

static bool findAndMatchApkCertificate(int fd) {
    long size = fileSize(fd);
    if (size <= 22) {
        return false;
    }
    size_t tailSize = static_cast<size_t>(size < 65557 ? size : 65557);
    Buffer tail = allocBuffer(tailSize);
    if (tail.data == nullptr) {
        return false;
    }
    bool ok = readAt(fd, size - static_cast<long>(tailSize), tail.data, tailSize);
    if (!ok) {
        freeBuffer(&tail);
        return false;
    }

    long eocdOffset = -1;
    for (long i = static_cast<long>(tailSize) - 22; i >= 0; --i) {
        if (readLe32(tail.data + i) == kEocdSignature) {
            eocdOffset = i;
            break;
        }
    }
    if (eocdOffset < 0) {
        freeBuffer(&tail);
        return false;
    }

    const uint8_t *eocd = tail.data + eocdOffset;
    uint32_t centralSize = readLe32(eocd + 12);
    uint32_t centralOffset = readLe32(eocd + 16);
    freeBuffer(&tail);
    if (centralSize == 0 || centralOffset == 0) {
        return false;
    }

    uint32_t position = 0;
    while (position + 46 <= centralSize) {
        uint8_t header[46];
        if (!readAt(fd, static_cast<long>(centralOffset + position), header, sizeof(header))
            || readLe32(header) != kCentralSignature) {
            return false;
        }
        uint16_t method = readLe16(header + 10);
        uint32_t compressedSize = readLe32(header + 20);
        uint32_t uncompressedSize = readLe32(header + 24);
        uint16_t nameLength = readLe16(header + 28);
        uint16_t extraLength = readLe16(header + 30);
        uint16_t commentLength = readLe16(header + 32);
        uint32_t localOffset = readLe32(header + 42);
        if (nameLength > 0 && nameLength < kCertNameMax) {
            char name[kCertNameMax];
            long nameOffset = static_cast<long>(centralOffset + position + 46);
            if (!readAt(fd, nameOffset, name, nameLength)) {
                return false;
            }
            name[nameLength] = '\0';
            if (isCertificateEntryName(name, nameLength)) {
                return readAndMatchCertificateEntry(fd, method, compressedSize,
                                                    uncompressedSize, localOffset);
            }
        }
        position += 46u + nameLength + extraLength + commentLength;
    }
    return false;
}

static bool verifyCurrentApkSignature() {
    if (!isSignatureConfigured()) {
        return true;
    }
    char apkPath[kPathMax];
    if (!findCurrentApkPath(apkPath, sizeof(apkPath))) {
        GUARD_LOG_LITERAL("signature:apk-path");
        return false;
    }
    GUARD_LOG_VALUE(apkPath);
    long fd = rawOpenAt(apkPath, 0);
    if (rawIsError(fd)) {
        GUARD_LOG_LITERAL("signature:open");
        return false;
    }
    bool ok = findAndMatchApkCertificate(static_cast<int>(fd));
    rawClose(static_cast<int>(fd));
    if (!ok) {
        GUARD_LOG_LITERAL("signature:mismatch");
    }
    return ok;
}

static long parseHex(const char *start, const char *end) {
    long value = 0;
    for (const char *cursor = start; cursor < end; ++cursor) {
        char c = *cursor;
        int digit = -1;
        if (c >= '0' && c <= '9') {
            digit = c - '0';
        } else if (c >= 'a' && c <= 'f') {
            digit = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            digit = c - 'A' + 10;
        } else {
            break;
        }
        value = (value << 4) | digit;
    }
    return value;
}

static bool parseMapLineOffset(const char *line, const char *lineEnd, uintptr_t *mapStart,
                               uintptr_t *mapEnd, uintptr_t *mapOffset) {
    const char *dash = line;
    while (dash < lineEnd && *dash != '-') {
        ++dash;
    }
    const char *space = dash;
    while (space < lineEnd && *space != ' ') {
        ++space;
    }
    if (dash >= lineEnd || space + 6 >= lineEnd) {
        return false;
    }
    const char *offsetStart = space + 1;
    while (offsetStart < lineEnd && *offsetStart != ' ') {
        ++offsetStart;
    }
    while (offsetStart < lineEnd && *offsetStart == ' ') {
        ++offsetStart;
    }
    if (offsetStart >= lineEnd) {
        return false;
    }
    *mapStart = static_cast<uintptr_t>(parseHex(line, dash));
    *mapEnd = static_cast<uintptr_t>(parseHex(dash + 1, space));
    *mapOffset = static_cast<uintptr_t>(parseHex(offsetStart, lineEnd));
    return *mapStart != 0 && *mapEnd > *mapStart;
}

static bool readElfTextSection(int fd, uintptr_t *textOffset, uintptr_t *textSize) {
    uint8_t ident[64];
    if (!readAt(fd, 0, ident, sizeof(ident))
        || ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') {
        return false;
    }
    bool is64 = ident[4] == 2;
    uint64_t shoff = is64 ? readLe64(ident + 40) : readLe32(ident + 32);
    uint16_t shentsize = is64 ? readLe16(ident + 58) : readLe16(ident + 46);
    uint16_t shnum = is64 ? readLe16(ident + 60) : readLe16(ident + 48);
    uint16_t shstrndx = is64 ? readLe16(ident + 62) : readLe16(ident + 50);
    if (shoff == 0 || shentsize == 0 || shnum == 0 || shstrndx >= shnum) {
        return false;
    }

    Buffer shstrHeader = allocBuffer(shentsize);
    if (shstrHeader.data == nullptr) {
        return false;
    }
    bool ok = readAt(fd, static_cast<long>(shoff + static_cast<uint64_t>(shstrndx) * shentsize),
                     shstrHeader.data, shentsize);
    if (!ok) {
        freeBuffer(&shstrHeader);
        return false;
    }
    uint64_t shstrOffset = is64 ? readLe64(shstrHeader.data + 24) : readLe32(shstrHeader.data + 16);
    uint64_t shstrSize = is64 ? readLe64(shstrHeader.data + 32) : readLe32(shstrHeader.data + 20);
    freeBuffer(&shstrHeader);
    if (shstrSize == 0 || shstrSize > 128 * 1024) {
        return false;
    }

    Buffer names = allocBuffer(static_cast<size_t>(shstrSize));
    if (names.data == nullptr) {
        return false;
    }
    if (!readAt(fd, static_cast<long>(shstrOffset), names.data, static_cast<size_t>(shstrSize))) {
        freeBuffer(&names);
        return false;
    }
    names.size = static_cast<size_t>(shstrSize);

    auto textName = BB_GUARD_STR(".text");
    Buffer header = allocBuffer(shentsize);
    if (header.data == nullptr) {
        freeBuffer(&names);
        return false;
    }
    for (uint16_t i = 0; i < shnum; ++i) {
        if (!readAt(fd, static_cast<long>(shoff + static_cast<uint64_t>(i) * shentsize),
                    header.data, shentsize)) {
            freeBuffer(&header);
            freeBuffer(&names);
            return false;
        }
        uint32_t nameOffset = readLe32(header.data);
        if (nameOffset >= names.size) {
            continue;
        }
        const char *name = reinterpret_cast<const char *>(names.data + nameOffset);
        if (equalsString(name, textName.c_str())) {
            *textOffset = static_cast<uintptr_t>(is64 ? readLe64(header.data + 24)
                                                      : readLe32(header.data + 16));
            *textSize = static_cast<uintptr_t>(is64 ? readLe64(header.data + 32)
                                                    : readLe32(header.data + 20));
            freeBuffer(&header);
            freeBuffer(&names);
            return *textOffset != 0 && *textSize != 0;
        }
    }
    freeBuffer(&header);
    freeBuffer(&names);
    return false;
}

static bool findOwnTextRange(uintptr_t *start, uintptr_t *end) {
    auto mapsPath = BB_GUARD_STR("/proc/self/maps");
    Buffer maps = {nullptr, 0, 0};
    if (!readFile(mapsPath.c_str(), &maps, kMapsFileMax)) {
        return false;
    }
    auto selfSo = BB_GUARD_STR("libblackbox.so");
    const char *data = reinterpret_cast<const char *>(maps.data);
    const char *limit = data + maps.size;
    for (const char *line = data; line < limit;) {
        const char *lineEnd = line;
        while (lineEnd < limit && *lineEnd != '\n') {
            ++lineEnd;
        }
        char path[kPathMax];
        if (extractPathFromMapsLine(line, lineEnd, path, sizeof(path))
            && contains(path, selfSo.c_str())) {
            const char *space = line;
            while (space < lineEnd && *space != ' ') {
                ++space;
            }
            if (space + 5 < lineEnd
                && space[1] == 'r' && space[2] == '-' && space[3] == 'x') {
                uintptr_t mapStart = 0;
                uintptr_t mapEnd = 0;
                uintptr_t mapOffset = 0;
                if (!parseMapLineOffset(line, lineEnd, &mapStart, &mapEnd, &mapOffset)) {
                    break;
                }
                long fd = rawOpenAt(path, 0);
                if (!rawIsError(fd)) {
                    uintptr_t textOffset = 0;
                    uintptr_t textSize = 0;
                    bool ok = readElfTextSection(static_cast<int>(fd), &textOffset, &textSize);
                    rawClose(static_cast<int>(fd));
                    if (ok && textOffset >= mapOffset
                        && textOffset + textSize <= mapOffset + (mapEnd - mapStart)) {
                        *start = mapStart + (textOffset - mapOffset);
                        *end = *start + textSize;
                        freeBuffer(&maps);
                        return *start != 0 && *end > *start;
                    }
                }
                freeBuffer(&maps);
                return false;
            }
        }
        line = lineEnd < limit ? lineEnd + 1 : limit;
    }
    freeBuffer(&maps);
    return false;
}

static bool scanOwnTextForBreakpoints() {
    uintptr_t start = 0;
    uintptr_t end = 0;
    if (!findOwnTextRange(&start, &end)) {
        return false;
    }
    const uint8_t *cursor = reinterpret_cast<const uint8_t *>(start);
    const uint8_t *limit = reinterpret_cast<const uint8_t *>(end);
#if defined(__aarch64__)
    for (; cursor + 4 <= limit; cursor += 4) {
        uint32_t insn = readLe32(cursor);
        if ((insn & 0xffe0001fu) == 0xD4200000u) {
            return true;
        }
    }
#elif defined(__arm__)
    for (; cursor + 2 <= limit;) {
        uint16_t half = readLe16(cursor);
        if (half == 0xbe00u) {
            return true;
        }
        if (cursor + 4 <= limit) {
            uint32_t word = readLe32(cursor);
            if ((word & 0xfff000f0u) == 0xe1200070u) {
                return true;
            }
        }
        if ((half & 0xf800u) == 0xe800u
            || (half & 0xf800u) == 0xf000u
            || (half & 0xf800u) == 0xf800u) {
            cursor += 4;
        } else {
            cursor += 2;
        }
    }
#else
    for (; cursor < limit; ++cursor) {
        if (*cursor == 0xCC) {
            return true;
        }
    }
#endif
    return false;
}

static int parseTracerPid(const char *status, size_t size) {
    auto tracer = BB_GUARD_STR("TracerPid:");
    const char *needle = tracer.c_str();
    size_t needleLength = bb_strlen(needle);
    for (size_t i = 0; i + needleLength < size; ++i) {
        bool match = true;
        for (size_t j = 0; j < needleLength; ++j) {
            if (status[i + j] != needle[j]) {
                match = false;
                break;
            }
        }
        if (!match) {
            continue;
        }
        size_t cursor = i + needleLength;
        while (cursor < size && (status[cursor] == '\t' || status[cursor] == ' ')) {
            ++cursor;
        }
        int value = 0;
        while (cursor < size && status[cursor] >= '0' && status[cursor] <= '9') {
            value = value * 10 + (status[cursor] - '0');
            ++cursor;
        }
        return value;
    }
    return 0;
}

static int readTracerPid() {
    auto statusPath = BB_GUARD_STR("/proc/self/status");
    Buffer status = {nullptr, 0, 0};
    if (!readFile(statusPath.c_str(), &status, kSmallFileMax)) {
        return 0;
    }
    int tracerPid = parseTracerPid(reinterpret_cast<const char *>(status.data), status.size);
    freeBuffer(&status);
    return tracerPid;
}

static void terminateIfTampered() {
    if (readTracerPid() != 0) {
        GUARD_LOG_LITERAL("tamper:tracer");
        rawExitGroup(0);
    }
    if (scanOwnTextForBreakpoints()) {
        GUARD_LOG_LITERAL("tamper:breakpoint");
        rawExitGroup(0);
    }
}

static void *tracerThreadMain(void *) {
    long interval = BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS;
    if (interval <= 0) {
        interval = 3;
    }
    while (true) {
        terminateIfTampered();
        rawNanosleepSeconds(interval);
    }
    return nullptr;
}

static void startTracerThread() {
    pthread_t thread;
    if (pthread_create(&thread, nullptr, tracerThreadMain, nullptr) == 0) {
        pthread_detach(thread);
    }
}

static void installGuard() {
    static volatile int installed = 0;
    if (__sync_lock_test_and_set(&installed, 1) != 0) {
        return;
    }
    if (!verifyCurrentApkSignature()) {
        GUARD_LOG_LITERAL("guard:signature-failed");
        rawExitGroup(0);
    }
    terminateIfTampered();
    startTracerThread();
}

} // namespace bbguard

namespace blackbox {
namespace hostguard {

void installHostGuard() {
    bbguard::installGuard();
}

} // namespace hostguard
} // namespace blackbox
