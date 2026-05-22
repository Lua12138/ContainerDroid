//
// Created by Milk on 4/9/21.
//

#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include <JniHook/JniHook.h>
#include <Hook/VMClassLoaderHook.h>
#include <Hook/UnixFileSystemHook.h>
#include <Hook/BinderHook.h>
#include <Hook/JniDiagnosticsHook.h>
#include <Hook/RuntimeHook.h>
#include "Utils/HexDump.h"
#include "SeccompShield.h"
#include "RawSyscallTerminationProbe.h"
#include <algorithm>
#include <cerrno>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <set>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

void *fake_dlopen(const char *libpath, int flags);
void *fake_dlsym(void *handle, const char *name);

extern "C" void setNativeSandboxEnvironment(const char *package_name, const char *process_name);
extern "C" void setNativeSandboxEnvironmentPackage(const char *package_name);
extern "C" void setNativeTerminationShieldPackage(const char *package_name);
extern "C" void disableEarlyProcMapsShim();
extern "C" void enterNativeInternalFileProbe();
extern "C" void leaveNativeInternalFileProbe();
extern "C" bool writeSanitizedProcMapsSnapshot(const char *output_path, const char *package_name);
extern "C" void installNativeFileHooks();
extern "C" void setNativeFileVirtualUid(int virtual_uid);

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID loadEmptyDex;
    jmethodID loadEmptyDexL;
    jmethodID getFileSystemClass;
    jmethodID findMethod;
    int api_level;
} VMEnv;


JNIEnv *getEnv() {
    JNIEnv *env;
    VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == NULL) {
        VMEnv.vm->AttachCurrentThread(&env, NULL);
    }
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    env = ensureEnvCreated();
    return env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    env = ensureEnvCreated();
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

jlongArray BoxCore::loadEmptyDex(JNIEnv *env) {
    env = ensureEnvCreated();
    return (jlongArray) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.loadEmptyDex);
}

jclass BoxCore::getNativeCoreClass() {
    return VMEnv.NativeCoreClass;
}

jobject BoxCore::getFileSystemClass(JNIEnv *env) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.getFileSystemClass);
}

jobject BoxCore::findMethod(JNIEnv *env, jclass clazz, const char *name, const char *desc) {
    env = ensureEnvCreated();
    jstring methodName = env->NewStringUTF(name);
    jstring methodDesc = env->NewStringUTF(desc);
    jobject method = env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.findMethod, clazz, methodName, methodDesc);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        method = nullptr;
    }
    env->DeleteLocalRef(methodName);
    env->DeleteLocalRef(methodDesc);
    return method;
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    JniDiagnosticsHook::init(env);
    UnixFileSystemHook::init(env);
    VMClassLoaderHook::init(env);
    RuntimeHook::init(env);
    BinderHook::init(env);
    installNativeFileHooks();
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(env->FindClass(VMCORE_CLASS));
    VMEnv.getCallingUidId = env->GetStaticMethodID(VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                      "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath",
                                                    "(Ljava/io/File;)Ljava/io/File;");
    VMEnv.loadEmptyDex = env->GetStaticMethodID(VMEnv.NativeCoreClass, "loadEmptyDex",
                                                "()[J");
    VMEnv.getFileSystemClass = env->GetStaticMethodID(VMEnv.NativeCoreClass, "getFileSystemClass",
                                                      "()Ljava/lang/Class;");
    VMEnv.findMethod = env->GetStaticMethodID(VMEnv.NativeCoreClass, "findMethod",
                                              "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/reflect/Method;");

    JniHook::InitJniHook(env, api_level);
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,
               jstring relocate_path) {
    IO::addRule(env->GetStringUTFChars(target_path, JNI_FALSE),
                env->GetStringUTFChars(relocate_path, JNI_FALSE));
}

void enableIO(JNIEnv *env, jclass clazz) {
    IO::init(env);
    nativeHook(env);
}

void installSeccompShield(JNIEnv *env, jclass clazz) {
    blackbox::seccomp::installSeccompShield();
}

void installTerminationOnlySeccompShield(JNIEnv *env, jclass clazz) {
    blackbox::seccomp::installTerminationOnlySeccompShield();
}

void installTerminationTrapSeccompShield(JNIEnv *env, jclass clazz) {
    blackbox::seccomp::installTerminationTrapSeccompShield();
}

void installRawSyscallEnvironmentProbe(JNIEnv *env, jclass clazz) {
    blackbox::rawsyscall::installRawSyscallEnvironmentProbe();
}

void installRawSyscallTerminationProbe(JNIEnv *env, jclass clazz) {
    blackbox::rawsyscall::installRawSyscallTerminationProbe();
}

void setVirtualUid(JNIEnv *env, jclass clazz, jint virtualUid) {
    blackbox::seccomp::setVirtualUid(virtualUid);
    setNativeFileVirtualUid(virtualUid);
}

void configureNativeSandboxEnvironment(JNIEnv *env, jclass clazz, jstring packageName) {
    const char *package_name = packageName == nullptr ? nullptr : env->GetStringUTFChars(packageName, JNI_FALSE);
    setNativeSandboxEnvironmentPackage(package_name);
    if (package_name != nullptr) {
        env->ReleaseStringUTFChars(packageName, package_name);
    }
}

void configureNativeSandboxEnvironmentWithProcess(JNIEnv *env, jclass clazz, jstring packageName, jstring processName) {
    const char *package_name = packageName == nullptr ? nullptr : env->GetStringUTFChars(packageName, JNI_FALSE);
    const char *process_name = processName == nullptr ? nullptr : env->GetStringUTFChars(processName, JNI_FALSE);
    setNativeSandboxEnvironment(package_name, process_name);
    if (process_name != nullptr) {
        env->ReleaseStringUTFChars(processName, process_name);
    }
    if (package_name != nullptr) {
        env->ReleaseStringUTFChars(packageName, package_name);
    }
}

void configureNativeTerminationShield(JNIEnv *env, jclass clazz, jstring packageName) {
    const char *package_name = packageName == nullptr ? nullptr : env->GetStringUTFChars(packageName, JNI_FALSE);
    setNativeTerminationShieldPackage(package_name);
    if (package_name != nullptr) {
        env->ReleaseStringUTFChars(packageName, package_name);
    }
}

void disableNativeEarlyProcMapsShim(JNIEnv *env, jclass clazz) {
    disableEarlyProcMapsShim();
}

void enterNativeInternalFileProbeJni(JNIEnv *env, jclass clazz) {
    enterNativeInternalFileProbe();
}

void leaveNativeInternalFileProbeJni(JNIEnv *env, jclass clazz) {
    leaveNativeInternalFileProbe();
}

jboolean writeSanitizedProcMapsSnapshotJni(JNIEnv *env, jclass clazz, jstring outputPath,
                                           jstring packageName) {
    const char *output_path = outputPath == nullptr ? nullptr : env->GetStringUTFChars(outputPath, JNI_FALSE);
    const char *package_name = packageName == nullptr ? nullptr : env->GetStringUTFChars(packageName, JNI_FALSE);
    bool ok = writeSanitizedProcMapsSnapshot(output_path, package_name);
    if (package_name != nullptr) {
        env->ReleaseStringUTFChars(packageName, package_name);
    }
    if (output_path != nullptr) {
        env->ReleaseStringUTFChars(outputPath, output_path);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

void enableBinderMonitor(JNIEnv *env, jclass clazz, jboolean recordNative, jboolean recordIoctl) {
    BinderHook::configureBinderMonitor(recordNative == JNI_TRUE, recordIoctl == JNI_TRUE);
}

static std::set<std::string> gDumpedDexCookieSignatures;
static int gDexCookieDumpSeq = 0;

static uint32_t readLe32(const uint8_t *data) {
    return static_cast<uint32_t>(data[0])
           | (static_cast<uint32_t>(data[1]) << 8)
           | (static_cast<uint32_t>(data[2]) << 16)
           | (static_cast<uint32_t>(data[3]) << 24);
}

static std::string bytesToHex(const uint8_t *data, size_t size) {
    static const char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(size * 2);
    for (size_t i = 0; i < size; i++) {
        out.push_back(kHex[(data[i] >> 4) & 0xf]);
        out.push_back(kHex[data[i] & 0xf]);
    }
    return out;
}

static uint32_t rotateLeft(uint32_t value, uint32_t bits) {
    return (value << bits) | (value >> (32 - bits));
}

static void sha1ProcessBlock(const uint8_t *block, uint32_t state[5]) {
    uint32_t w[80];
    for (int i = 0; i < 16; i++) {
        w[i] = (static_cast<uint32_t>(block[i * 4]) << 24)
               | (static_cast<uint32_t>(block[i * 4 + 1]) << 16)
               | (static_cast<uint32_t>(block[i * 4 + 2]) << 8)
               | static_cast<uint32_t>(block[i * 4 + 3]);
    }
    for (int i = 16; i < 80; i++) {
        w[i] = rotateLeft(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    }

    uint32_t a = state[0];
    uint32_t b = state[1];
    uint32_t c = state[2];
    uint32_t d = state[3];
    uint32_t e = state[4];

    for (int i = 0; i < 80; i++) {
        uint32_t f;
        uint32_t k;
        if (i < 20) {
            f = (b & c) | ((~b) & d);
            k = 0x5a827999;
        } else if (i < 40) {
            f = b ^ c ^ d;
            k = 0x6ed9eba1;
        } else if (i < 60) {
            f = (b & c) | (b & d) | (c & d);
            k = 0x8f1bbcdc;
        } else {
            f = b ^ c ^ d;
            k = 0xca62c1d6;
        }
        uint32_t temp = rotateLeft(a, 5) + f + e + k + w[i];
        e = d;
        d = c;
        c = rotateLeft(b, 30);
        b = a;
        a = temp;
    }

    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
}

static std::string sha1Hex(const uint8_t *data, size_t size) {
    uint32_t state[5] = {
            0x67452301,
            0xefcdab89,
            0x98badcfe,
            0x10325476,
            0xc3d2e1f0
    };

    size_t offset = 0;
    while (offset + 64 <= size) {
        sha1ProcessBlock(data + offset, state);
        offset += 64;
    }

    uint8_t tail[128];
    size_t remaining = size - offset;
    memset(tail, 0, sizeof(tail));
    if (remaining > 0) {
        memcpy(tail, data + offset, remaining);
    }
    tail[remaining] = 0x80;

    size_t finalBlockCount = remaining >= 56 ? 2 : 1;
    uint64_t bitLength = static_cast<uint64_t>(size) * 8;
    uint8_t *lengthSlot = tail + (finalBlockCount * 64) - 8;
    for (int i = 7; i >= 0; i--) {
        lengthSlot[7 - i] = static_cast<uint8_t>((bitLength >> (i * 8)) & 0xff);
    }
    for (size_t i = 0; i < finalBlockCount; i++) {
        sha1ProcessBlock(tail + i * 64, state);
    }

    uint8_t digest[20];
    for (int i = 0; i < 5; i++) {
        digest[i * 4] = static_cast<uint8_t>((state[i] >> 24) & 0xff);
        digest[i * 4 + 1] = static_cast<uint8_t>((state[i] >> 16) & 0xff);
        digest[i * 4 + 2] = static_cast<uint8_t>((state[i] >> 8) & 0xff);
        digest[i * 4 + 3] = static_cast<uint8_t>(state[i] & 0xff);
    }
    return bytesToHex(digest, sizeof(digest));
}

static bool isDexMagic(const uint8_t *data) {
    return data[0] == 'd'
           && data[1] == 'e'
           && data[2] == 'x'
           && data[3] == '\n'
           && data[4] >= '0'
           && data[4] <= '9'
           && data[5] >= '0'
           && data[5] <= '9'
           && data[6] >= '0'
           && data[6] <= '9'
           && data[7] == '\0';
}

static bool isLikelyDexHeader(const uint8_t *data, size_t available, uint32_t *fileSize) {
    if (available < 0x70 || !isDexMagic(data)) {
        return false;
    }
    uint32_t size = readLe32(data + 0x20);
    uint32_t headerSize = readLe32(data + 0x24);
    uint32_t endianTag = readLe32(data + 0x28);
    if (size < 0x70 || size > 128 * 1024 * 1024) {
        return false;
    }
    if (headerSize != 0x70 || endianTag != 0x12345678) {
        return false;
    }
    *fileSize = size;
    return true;
}

typedef FILE *(*FopenFn)(const char *pathname, const char *mode);

static FILE *openRealProcMapsFileForMemoryProbe() {
    static FopenFn real_fopen = nullptr;
    if (real_fopen == nullptr) {
        real_fopen = reinterpret_cast<FopenFn>(dlsym(RTLD_NEXT, "fopen"));
    }
    if (real_fopen == nullptr) {
        return nullptr;
    }
    return real_fopen("/proc/self/maps", "r");
}

static bool isReadableMemoryRange(const void *address, size_t size) {
    uintptr_t target = reinterpret_cast<uintptr_t>(address);
    uintptr_t targetEnd = target + size;
    if (target == 0 || size == 0 || targetEnd < target) {
        return false;
    }

    FILE *maps = openRealProcMapsFileForMemoryProbe();
    if (maps == nullptr) {
        return false;
    }

    bool readable = false;
    char line[256];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long start = 0;
        unsigned long end = 0;
        char perms[5] = {};
        if (sscanf(line, "%lx-%lx %4s", &start, &end, perms) == 3
            && perms[0] == 'r'
            && target >= static_cast<uintptr_t>(start)
            && targetEnd <= static_cast<uintptr_t>(end)) {
            readable = true;
            break;
        }
    }
    fclose(maps);
    return readable;
}

static bool readDexCookiePointer(uintptr_t object, size_t offset, const uint8_t **value) {
    uintptr_t slot = object + offset;
    if (slot < object || !isReadableMemoryRange(reinterpret_cast<void *>(slot), sizeof(void *))) {
        return false;
    }
    *value = *reinterpret_cast<const uint8_t *const *>(slot);
    return true;
}

static bool readDexCookieSize(uintptr_t object, size_t offset, size_t *value) {
    uintptr_t slot = object + offset;
    if (slot < object || !isReadableMemoryRange(reinterpret_cast<void *>(slot), sizeof(size_t))) {
        return false;
    }
    *value = *reinterpret_cast<const size_t *>(slot);
    return true;
}

static bool findDexFileMemory(jlong cookie, const uint8_t **begin, uint32_t *fileSize) {
    uintptr_t object = static_cast<uintptr_t>(cookie);
    if (object == 0 || !isReadableMemoryRange(reinterpret_cast<void *>(object), sizeof(void *) * 3)) {
        return false;
    }

    // AOSP art::DexFile stores begin_ and size_ as adjacent fields. Scan the
    // first few words instead of depending on one exact compiler layout.
    for (size_t offset = 0; offset <= 128; offset += sizeof(void *)) {
        const uint8_t *candidateBegin = nullptr;
        size_t mappedSize = 0;
        if (!readDexCookiePointer(object, offset, &candidateBegin)
            || !readDexCookieSize(object, offset + sizeof(void *), &mappedSize)) {
            continue;
        }
        if (candidateBegin == nullptr || mappedSize < 0x70 || mappedSize > 128 * 1024 * 1024) {
            continue;
        }
        if (!isReadableMemoryRange(candidateBegin, 0x70)) {
            continue;
        }
        uint32_t headerFileSize = 0;
        if (!isLikelyDexHeader(candidateBegin, 0x70, &headerFileSize)) {
            continue;
        }
        if (headerFileSize > mappedSize || !isReadableMemoryRange(candidateBegin, headerFileSize)) {
            continue;
        }
        *begin = candidateBegin;
        *fileSize = headerFileSize;
        return true;
    }
    return false;
}

static bool writeDexCookieFile(const char *outputDir, jlong cookie, const uint8_t *begin, uint32_t size) {
    uint32_t checksum = readLe32(begin + 8);
    std::string sha1 = sha1Hex(begin, size);
    std::string key = "cookie:" + sha1;
    if (gDumpedDexCookieSignatures.find(key) != gDumpedDexCookieSignatures.end()) {
        return false;
    }
    gDumpedDexCookieSignatures.insert(key);

    char outputPath[1024];
    snprintf(outputPath, sizeof(outputPath),
             "%s/cookie_%s.dex",
             outputDir, sha1.c_str());

    int fd = open(outputPath, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        ALOGE("dumpDex cookie open failed cookie=%" PRIx64 " path=%s errno=%d",
              static_cast<uint64_t>(cookie), outputPath, errno);
        return false;
    }

    size_t written = 0;
    while (written < size) {
        ssize_t n = write(fd, begin + written, size - written);
        if (n <= 0) {
            close(fd);
            ALOGE("dumpDex cookie write failed cookie=%" PRIx64 " path=%s errno=%d",
                  static_cast<uint64_t>(cookie), outputPath, errno);
            return false;
        }
        written += static_cast<size_t>(n);
    }
    close(fd);
    ALOGD("dumpDex cookie=%" PRIx64 " addr=%p bytes=%u checksum=%08x sha1=%s seq=%d out=%s",
          static_cast<uint64_t>(cookie), begin, size, checksum, sha1.c_str(),
          gDexCookieDumpSeq++, outputPath);
    return true;
}

jboolean dumpDexCookieNative(JNIEnv *env, jclass clazz, jlong cookie, jstring outputDirString) {
    if (cookie == 0 || outputDirString == nullptr) {
        return JNI_FALSE;
    }
    const char *outputDir = env->GetStringUTFChars(outputDirString, JNI_FALSE);
    if (outputDir == nullptr) {
        return JNI_FALSE;
    }
    mkdir(outputDir, 0700);

    const uint8_t *begin = nullptr;
    uint32_t fileSize = 0;
    bool dumped = false;
    if (findDexFileMemory(cookie, &begin, &fileSize)) {
        dumped = writeDexCookieFile(outputDir, cookie, begin, fileSize);
    }
    env->ReleaseStringUTFChars(outputDirString, outputDir);
    return dumped ? JNI_TRUE : JNI_FALSE;
}

static bool isReadableProcessAddress(const void *address) {
    return isReadableMemoryRange(address, sizeof(void *));
}

static void *getArtSymbol(const char *symbol) {
    void *address = dlsym(RTLD_DEFAULT, symbol);
    if (address != nullptr) {
        return address;
    }

    void *handle = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen("/apex/com.android.art/lib/libart.so", RTLD_NOW | RTLD_NOLOAD);
    }
    if (handle == nullptr) {
        handle = dlopen("libart.so", RTLD_NOW);
    }
    address = handle == nullptr ? nullptr : dlsym(handle, symbol);
    if (address != nullptr) {
        return address;
    }

#if defined(__LP64__)
    static const char *kArtLibPaths[] = {
            "/apex/com.android.art/lib64/libart.so",
            "/system/lib64/libart.so",
    };
#else
    static const char *kArtLibPaths[] = {
            "/apex/com.android.art/lib/libart.so",
            "/system/lib/libart.so",
    };
#endif
    static void *fakeArtHandle = nullptr;
    if (fakeArtHandle == nullptr) {
        for (const char *path : kArtLibPaths) {
            fakeArtHandle = fake_dlopen(path, 0);
            if (fakeArtHandle != nullptr) {
                break;
            }
        }
    }
    return fakeArtHandle == nullptr ? nullptr : fake_dlsym(fakeArtHandle, symbol);
}

static bool readProcessPointer(void *base, size_t offset, void **value) {
    void *slot = reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(base) + offset);
    if (!isReadableProcessAddress(slot)) {
        return false;
    }
    *value = *reinterpret_cast<void **>(slot);
    return true;
}

static int findRuntimeJavaVmOffset(void *runtime) {
    if (runtime == nullptr || VMEnv.vm == nullptr) {
        return -1;
    }
    for (size_t offset = 0; offset <= 1024 - sizeof(void *); offset += sizeof(void *)) {
        void *candidate = nullptr;
        if (readProcessPointer(runtime, offset, &candidate) && candidate == VMEnv.vm) {
            return static_cast<int>(offset);
        }
    }
    return -1;
}

static JNINativeMethod gMethods[] = {
        {"hideXposed",          "()V",                                     (void *) hideXposed},
        {"addIORule",           "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",            "()V",                                     (void *) enableIO},
        {"installSeccompShield","()V",                                     (void *) installSeccompShield},
        {"installTerminationOnlySeccompShield", "()V",                     (void *) installTerminationOnlySeccompShield},
        {"installTerminationTrapSeccompShield", "()V",                     (void *) installTerminationTrapSeccompShield},
        {"installRawSyscallEnvironmentProbe", "()V",                       (void *) installRawSyscallEnvironmentProbe},
        {"installRawSyscallTerminationProbe", "()V",                       (void *) installRawSyscallTerminationProbe},
        {"setVirtualUid",       "(I)V",                                    (void *) setVirtualUid},
        {"setNativeSandboxEnvironment", "(Ljava/lang/String;Ljava/lang/String;)V", (void *) configureNativeSandboxEnvironmentWithProcess},
        {"setNativeSandboxEnvironmentPackage", "(Ljava/lang/String;)V",    (void *) configureNativeSandboxEnvironment},
        {"setNativeTerminationShieldPackage", "(Ljava/lang/String;)V",     (void *) configureNativeTerminationShield},
        {"disableEarlyProcMapsShim", "()V",                                (void *) disableNativeEarlyProcMapsShim},
        {"enterNativeInternalFileProbe", "()V",                             (void *) enterNativeInternalFileProbeJni},
        {"leaveNativeInternalFileProbe", "()V",                             (void *) leaveNativeInternalFileProbeJni},
        {"writeSanitizedProcMapsSnapshot", "(Ljava/lang/String;Ljava/lang/String;)Z", (void *) writeSanitizedProcMapsSnapshotJni},
        {"enableBinderMonitor", "(ZZ)V",                                   (void *) enableBinderMonitor},
        {"dumpDexCookieNative", "(JLjava/lang/String;)Z",                   (void *) dumpDexCookieNative},
        {"init",                "(I)V",                                    (void *) init},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,
                               sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

void registerMethod(JNIEnv *jenv) {
    registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    registerMethod(env);
    return JNI_VERSION_1_6;
}
