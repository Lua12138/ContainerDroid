//
// Created by Milk on 5/5/21.
//

#include "RuntimeHook.h"
#import "JniHook/JniHook.h"
#include "BoxCore.h"
#include "IO.h"
#include "Utils/NativeProperty.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <string>

extern "C" void installNativeFileHooks();
extern "C" void refreshProtectedProcMapsShim();
extern "C" const char *currentNativeHostPackage();

namespace {

#define kDeniedProcVersion BB_CORE_STR("/proc/version")
#define kDeniedProcMaps BB_CORE_STR("/proc/self/maps")
#define kDeniedProcMeminfo BB_CORE_STR("/proc/meminfo")
#define kDeniedProcCmdline BB_CORE_STR("/proc/%d/cmdline")
#define kDeniedProcComm BB_CORE_STR("/proc/%d/comm")
#define kProcVersionFdPath BB_CORE_STR("/dev/fd/94")
#define kProcMapsFdPath BB_CORE_STR("/dev/fd/93")
#define kProcMeminfoFdPath BB_CORE_STR("/dev/fd/92")
#define kProcCmdlineFdPath BB_CORE_STR("/dev/fd/91")
#define kProcCommFdPath BB_CORE_STR("/dev/fd/90")
constexpr int kProcVersionFd = 94;
constexpr int kProcMapsFd = 93;
constexpr int kProcMeminfoFd = 92;
constexpr int kProcCmdlineFd = 91;
constexpr int kProcCommFd = 90;
constexpr size_t kProcCmdlineMinBytes = 76;
constexpr size_t kMaxPatchLibrarySize = 16 * 1024 * 1024;
#define kFakeProcVersion BB_CORE_STR("Linux version 4.19.127-perf-gbb0f387b2ec1 " \
        "(android-build@localhost) #1 SMP PREEMPT\n")

#define kBlackBoxUserMarker BB_CORE_STR("/blackbox/data/user/")
#define kProcShimProperty BB_CORE_STR("debug.blackbox.proc_shim")

struct ProcShimContext {
    char library_path[PATH_MAX];
    char host_package[128];
    char host_user_id[16];
    char package_name[128];
    char user_id[16];
    char virtual_data_root[PATH_MAX];
    char data_user_virtual_root[PATH_MAX];
    char data_data_virtual_root[PATH_MAX];
    char public_data_root[PATH_MAX];
};

using FakeFileWriter = bool (*)(int, const ProcShimContext &);

struct ProcPathShim {
    const char *denied_path;
    const char *fd_path;
    const char *file_name;
    int fd;
    FakeFileWriter writer;
};

int gProcShimFds[] = {-1, -1, -1, -1, -1};
ProcShimContext gProcShimContext = {};
bool gProcShimContextReady = false;
__thread bool gRefreshingProcMapsShim = false;
pthread_mutex_t gProcMapsRefreshMutex = PTHREAD_MUTEX_INITIALIZER;

bool contains(const char *value, const char *needle) {
    return value != nullptr && needle != nullptr && needle[0] != '\0' && strstr(value, needle) != nullptr;
}

bool isProcShimEnabled() {
    return blackbox::native_property::getBool(kProcShimProperty);
}

bool samePath(const char *left, const char *right) {
    if (left == nullptr || right == nullptr) {
        return left == right;
    }
    return strcmp(left, right) == 0;
}

bool shouldPrepareProcVersionShim(const char *library_path) {
    return contains(library_path, BB_CORE_STR("/blackbox/data/user/"))
           && contains(library_path, ".so");
}

jstring handleNativeLoadResult(JNIEnv *env, jstring result, const char *name, const char *loadPath) {
    if (result == nullptr) {
        return nullptr;
    }

    const char *error = env->GetStringUTFChars(result, JNI_FALSE);
    ALOGD("nativeLoad result name=%s redirected=%s error=%s",
          name == nullptr ? "null" : name,
          loadPath == nullptr ? "null" : loadPath,
          error == nullptr ? "null" : error);
    if (error != nullptr) {
        env->ReleaseStringUTFChars(result, error);
    }
    return result;
}

bool parentDir(const char *path, char *buffer, size_t buffer_size) {
    if (path == nullptr || buffer == nullptr || buffer_size == 0) {
        return false;
    }
    size_t length = strlen(path);
    if (length == 0 || length >= buffer_size) {
        return false;
    }
    memcpy(buffer, path, length + 1);
    char *slash = strrchr(buffer, '/');
    if (slash == nullptr || slash == buffer) {
        return false;
    }
    *slash = '\0';
    return true;
}

bool writeExact(int fd, const void *data, size_t length) {
    const uint8_t *cursor = reinterpret_cast<const uint8_t *>(data);
    size_t remaining = length;
    while (remaining > 0) {
        ssize_t written = write(fd, cursor, remaining);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        if (written == 0) {
            return false;
        }
        cursor += written;
        remaining -= static_cast<size_t>(written);
    }
    return true;
}

bool readExact(int fd, void *data, size_t length) {
    uint8_t *cursor = reinterpret_cast<uint8_t *>(data);
    size_t remaining = length;
    while (remaining > 0) {
        ssize_t bytes = read(fd, cursor, remaining);
        if (bytes < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        if (bytes == 0) {
            return false;
        }
        cursor += bytes;
        remaining -= static_cast<size_t>(bytes);
    }
    return true;
}

bool copyCStringPart(char *dest, size_t dest_size, const char *start, const char *end) {
    if (dest == nullptr || dest_size == 0 || start == nullptr || end == nullptr || end < start) {
        return false;
    }
    size_t length = static_cast<size_t>(end - start);
    if (length == 0 || length >= dest_size) {
        return false;
    }
    memcpy(dest, start, length);
    dest[length] = '\0';
    return true;
}

bool buildProcShimContext(const char *library_path, ProcShimContext *context) {
    if (library_path == nullptr || context == nullptr) {
        return false;
    }

    memset(context, 0, sizeof(*context));
    int copied = snprintf(context->library_path, sizeof(context->library_path), "%s", library_path);
    if (copied <= 0 || static_cast<size_t>(copied) >= sizeof(context->library_path)) {
        return false;
    }

    const char *marker = strstr(context->library_path, kBlackBoxUserMarker);
    if (marker == nullptr) {
        return false;
    }
    const char *host_start = marker;
    while (host_start > context->library_path && *(host_start - 1) != '/') {
        --host_start;
    }
    if (host_start < marker
        && copyCStringPart(context->host_package, sizeof(context->host_package),
                           host_start, marker)) {
        // parsed from the path
    } else {
        const char *configured_host = currentNativeHostPackage();
        if (configured_host != nullptr && configured_host[0] != '\0') {
            snprintf(context->host_package, sizeof(context->host_package), "%s", configured_host);
        }
    }
    snprintf(context->host_user_id, sizeof(context->host_user_id), "0");
    constexpr const char *data_user_prefix = "/data/user/";
    if (strncmp(context->library_path, data_user_prefix, strlen(data_user_prefix)) == 0) {
        const char *host_user_start = context->library_path + strlen(data_user_prefix);
        const char *host_user_end = strchr(host_user_start, '/');
        if (host_user_end != nullptr) {
            copyCStringPart(context->host_user_id, sizeof(context->host_user_id),
                            host_user_start, host_user_end);
        }
    }

    const char *user_start = marker + strlen(kBlackBoxUserMarker);
    const char *user_end = strchr(user_start, '/');
    if (user_end == nullptr || !copyCStringPart(context->user_id, sizeof(context->user_id),
                                                user_start, user_end)) {
        return false;
    }

    const char *package_start = user_end + 1;
    const char *package_end = strchr(package_start, '/');
    if (package_end == nullptr || !copyCStringPart(context->package_name, sizeof(context->package_name),
                                                   package_start, package_end)) {
        return false;
    }

    size_t virtual_root_len = static_cast<size_t>(package_end - context->library_path);
    if (virtual_root_len == 0 || virtual_root_len >= sizeof(context->virtual_data_root)) {
        return false;
    }
    memcpy(context->virtual_data_root, context->library_path, virtual_root_len);
    context->virtual_data_root[virtual_root_len] = '\0';

    int required = snprintf(context->public_data_root, sizeof(context->public_data_root),
                            BB_CORE_STR("/data/data/%s"), context->package_name);
    if (required <= 0 || static_cast<size_t>(required) >= sizeof(context->public_data_root)) {
        return false;
    }

    required = snprintf(context->data_user_virtual_root, sizeof(context->data_user_virtual_root),
                        BB_CORE_STR("/data/user/%s/%s/blackbox/data/user/%s/%s"),
                        context->host_user_id, context->host_package,
                        context->user_id, context->package_name);
    if (required <= 0 || static_cast<size_t>(required) >= sizeof(context->data_user_virtual_root)) {
        return false;
    }

    required = snprintf(context->data_data_virtual_root, sizeof(context->data_data_virtual_root),
                        BB_CORE_STR("/data/data/%s/blackbox/data/user/%s/%s"),
                        context->host_package, context->user_id, context->package_name);
    return required > 0 && static_cast<size_t>(required) < sizeof(context->data_data_virtual_root);
}

void replaceAll(std::string *value, const char *needle, const char *replacement) {
    if (value == nullptr || needle == nullptr || replacement == nullptr || needle[0] == '\0') {
        return;
    }
    size_t pos = 0;
    const size_t needle_len = strlen(needle);
    const size_t replacement_len = strlen(replacement);
    while ((pos = value->find(needle, pos)) != std::string::npos) {
        value->replace(pos, needle_len, replacement);
        pos += replacement_len;
    }
}

std::string sanitizeMapsLine(const char *line, const ProcShimContext &context) {
    std::string sanitized(line == nullptr ? "" : line);
    replaceAll(&sanitized, context.virtual_data_root, context.public_data_root);
    replaceAll(&sanitized, context.data_user_virtual_root, context.public_data_root);
    replaceAll(&sanitized, context.data_data_virtual_root, context.public_data_root);
    replaceAll(&sanitized, context.host_package, context.package_name);
    replaceAll(&sanitized, BB_CORE_STR("/blackbox/data/user/"), BB_CORE_STR("/data/user/"));
    replaceAll(&sanitized, BB_CORE_STR("/blackbox/"), BB_CORE_STR("/data/"));
    replaceAll(&sanitized, BB_CORE_STR("libblackbox.so"), BB_CORE_STR("libandroid_runtime.so"));
    return sanitized;
}

bool shouldHideMapsLine(const char *line, const ProcShimContext &context) {
    return contains(line, context.host_package)
           || contains(line, BB_CORE_STR("libblackbox.so"))
           || contains(line, BB_CORE_STR("libblackhook.so"))
           || contains(line, BB_CORE_STR("libblackdex.so"))
           || contains(line, BB_CORE_STR("libpine.so"))
           || contains(line, BB_CORE_STR("[anon:pine codes]"));
}

bool isVirtualAppDataLine(const char *line, const ProcShimContext &context) {
    return contains(line, context.virtual_data_root)
           || contains(line, context.data_user_virtual_root)
           || contains(line, context.data_data_virtual_root)
           || contains(line, context.public_data_root);
}

bool shouldHideRawMapsLine(const char *line, const ProcShimContext &context) {
    return !isVirtualAppDataLine(line, context) && shouldHideMapsLine(line, context);
}

bool copyRealFileToFd(const char *path, int fd) {
    int source = open(path, O_RDONLY | O_CLOEXEC);
    if (source < 0) {
        return false;
    }

    char buffer[4096];
    bool ok = true;
    for (;;) {
        ssize_t bytes = read(source, buffer, sizeof(buffer));
        if (bytes < 0) {
            if (errno == EINTR) {
                continue;
            }
            ok = false;
            break;
        }
        if (bytes == 0) {
            break;
        }
        if (!writeExact(fd, buffer, static_cast<size_t>(bytes))) {
            ok = false;
            break;
        }
    }
    close(source);
    return ok;
}

bool writeFakeVersionFile(int fd, const ProcShimContext &) {
    return writeExact(fd, kFakeProcVersion, strlen(kFakeProcVersion));
}

bool writeFakeCmdlineFile(int fd, const ProcShimContext &context) {
    size_t name_length = strlen(context.package_name);
    size_t length = name_length + 1 > kProcCmdlineMinBytes
                    ? name_length + 1
                    : kProcCmdlineMinBytes;
    std::string cmdline(length, '\0');
    memcpy(&cmdline[0], context.package_name, name_length);
    return writeExact(fd, cmdline.data(), cmdline.size());
}

bool writeFakeCommFile(int fd, const ProcShimContext &context) {
    const char *comm = context.package_name;
    size_t length = strlen(comm);
    if (length > 15) {
        comm += length - 15;
        length = 15;
    }
    return writeExact(fd, comm, length) && writeExact(fd, "\n", 1);
}

bool writeFakeMeminfoFile(int fd, const ProcShimContext &) {
    if (copyRealFileToFd(BB_CORE_STR("/proc/meminfo"), fd)) {
        return true;
    }
    constexpr const char *fallback =
            "MemTotal:        4096000 kB\n"
            "MemFree:         1024000 kB\n"
            "MemAvailable:    2048000 kB\n";
    return writeExact(fd, fallback, strlen(fallback));
}

using FopenFn = FILE *(*)(const char *, const char *);

FILE *openRealProcMapsFile() {
    static FopenFn real_fopen = nullptr;
    if (real_fopen == nullptr) {
        real_fopen = reinterpret_cast<FopenFn>(dlsym(RTLD_NEXT, "fopen"));
    }
    if (real_fopen == nullptr) {
        return nullptr;
    }
    return real_fopen(BB_CORE_STR("/proc/self/maps"), BB_CORE_STR("re"));
}

bool writeFakeMapsFile(int fd, const ProcShimContext &context) {
    FILE *maps = openRealProcMapsFile();
    if (maps == nullptr) {
        return false;
    }

    char line[4096];
    bool wrote_any = false;
    bool ok = true;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (shouldHideRawMapsLine(line, context)) {
            continue;
        }
        std::string sanitized = sanitizeMapsLine(line, context);
        if (shouldHideMapsLine(sanitized.c_str(), context)) {
            continue;
        }
        if (!writeExact(fd, sanitized.data(), sanitized.size())) {
            ok = false;
            break;
        }
        wrote_any = true;
    }
    fclose(maps);
    if (!ok) {
        return false;
    }
    return wrote_any;
}

const ProcPathShim kProcPathShims[] = {
        {kDeniedProcComm, kProcCommFdPath, BB_CORE_STR(".bb_proc_comm"), kProcCommFd, writeFakeCommFile},
        {kDeniedProcCmdline, kProcCmdlineFdPath, BB_CORE_STR(".bb_proc_cmdline"), kProcCmdlineFd, writeFakeCmdlineFile},
        {kDeniedProcMeminfo, kProcMeminfoFdPath, BB_CORE_STR(".bb_proc_meminfo"), kProcMeminfoFd, writeFakeMeminfoFile},
        {kDeniedProcMaps, kProcMapsFdPath, BB_CORE_STR(".bb_proc_maps"), kProcMapsFd, writeFakeMapsFile},
        {kDeniedProcVersion, kProcVersionFdPath, BB_CORE_STR(".bb_proc_version"), kProcVersionFd, writeFakeVersionFile},
};

bool buildProcShimFilePath(const ProcPathShim &shim, const ProcShimContext &context,
                           char *fake_path, size_t fake_path_size) {
    char dir[PATH_MAX];
    if (!parentDir(context.library_path, dir, sizeof(dir))) {
        return false;
    }

    int required = snprintf(fake_path, fake_path_size, "%s/%s", dir, shim.file_name);
    return required > 0 && static_cast<size_t>(required) < fake_path_size;
}

bool ensureProcShimFd(const ProcPathShim &shim, const ProcShimContext &context) {
    char fake_path[PATH_MAX];
    if (!buildProcShimFilePath(shim, context, fake_path, sizeof(fake_path))) {
        return false;
    }

    int fd = open(fake_path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        ALOGE("proc shim open failed: %s errno=%d", fake_path, errno);
        return false;
    }
    if (!shim.writer(fd, context) || lseek(fd, 0, SEEK_SET) < 0) {
        ALOGE("proc shim write failed: %s errno=%d", fake_path, errno);
        close(fd);
        return false;
    }

    if (fd != shim.fd) {
        if (dup2(fd, shim.fd) < 0) {
            ALOGE("proc shim dup2 failed: fd=%d target=%d errno=%d", fd, shim.fd, errno);
            close(fd);
            return false;
        }
        close(fd);
    }
    lseek(shim.fd, 0, SEEK_SET);
    return true;
}

bool refreshProcMapsShimAtomically(const ProcShimContext &context) {
    char fake_path[PATH_MAX];
    if (!buildProcShimFilePath(kProcPathShims[3], context, fake_path, sizeof(fake_path))) {
        return false;
    }

    char temp_path[PATH_MAX];
    int required = snprintf(temp_path, sizeof(temp_path), "%s.refresh.%d",
                            fake_path, getpid());
    if (required <= 0 || static_cast<size_t>(required) >= sizeof(temp_path)) {
        return false;
    }

    int fd = open(temp_path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        return false;
    }

    bool ok = writeFakeMapsFile(fd, context)
              && lseek(fd, 0, SEEK_SET) >= 0
              && rename(temp_path, fake_path) == 0
              && dup2(fd, kProcMapsFd) >= 0;
    if (!ok) {
        ALOGE("protected proc maps atomic refresh failed path=%s errno=%d", temp_path, errno);
        unlink(temp_path);
        close(fd);
        return false;
    }

    close(fd);
    lseek(kProcMapsFd, 0, SEEK_SET);
    return true;
}

bool ensureProcShimFds(const ProcShimContext &context) {
    bool ok = true;
    for (size_t i = 0; i < sizeof(kProcPathShims) / sizeof(kProcPathShims[0]); ++i) {
        if (!ensureProcShimFd(kProcPathShims[i], context)) {
            ok = false;
            continue;
        }
        gProcShimFds[i] = kProcPathShims[i].fd;
    }
    return ok;
}

struct ProcMapsRefreshThreadArgs {
    int iterations;
    useconds_t interval_us;
};

void *procMapsRefreshThreadMain(void *arg) {
    ProcMapsRefreshThreadArgs *args = reinterpret_cast<ProcMapsRefreshThreadArgs *>(arg);
    const int iterations = args == nullptr ? 0 : args->iterations;
    const useconds_t interval_us = args == nullptr ? 0 : args->interval_us;
    free(args);
    for (int i = 0; i < iterations; ++i) {
        refreshProtectedProcMapsShim();
        if (interval_us > 0) {
            usleep(interval_us);
        }
    }
    return nullptr;
}

void startProcMapsRefreshDuringNativeLoad() {
    if (!isProcShimEnabled() || !gProcShimContextReady) {
        return;
    }
    ProcMapsRefreshThreadArgs *args = reinterpret_cast<ProcMapsRefreshThreadArgs *>(
            calloc(1, sizeof(ProcMapsRefreshThreadArgs)));
    if (args == nullptr) {
        return;
    }
    args->iterations = 300;
    args->interval_us = 5000;

    pthread_t thread = {};
    if (pthread_create(&thread, nullptr, procMapsRefreshThreadMain, args) != 0) {
        free(args);
        return;
    }
    pthread_detach(thread);
}

bool patchProtectedProcStrings(const char *library_path, int *patch_count) {
    if (patch_count != nullptr) {
        *patch_count = 0;
    }

    int fd = open(library_path, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        ALOGE("proc-version shim library open failed: %s errno=%d", library_path, errno);
        return false;
    }

    struct stat st = {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0
        || static_cast<size_t>(st.st_size) > kMaxPatchLibrarySize) {
        close(fd);
        return false;
    }

    size_t size = static_cast<size_t>(st.st_size);
    uint8_t *content = reinterpret_cast<uint8_t *>(malloc(size));
    if (content == nullptr) {
        close(fd);
        return false;
    }

    bool ok = false;
    if (readExact(fd, content, size)) {
        int count = 0;
        for (size_t shim_index = 0; shim_index < sizeof(kProcPathShims) / sizeof(kProcPathShims[0]); ++shim_index) {
            const ProcPathShim &shim = kProcPathShims[shim_index];
            const size_t denied_len = strlen(shim.denied_path);
            const size_t fd_path_len = strlen(shim.fd_path);
            if (fd_path_len > denied_len) {
                continue;
            }
            for (size_t i = 0; i + denied_len <= size; ++i) {
                if (memcmp(content + i, shim.denied_path, denied_len) != 0) {
                    continue;
                }
                memcpy(content + i, shim.fd_path, fd_path_len);
                memset(content + i + fd_path_len, 0, denied_len - fd_path_len);
                ++count;
            }
        }

        if (count > 0 && lseek(fd, 0, SEEK_SET) == 0 && writeExact(fd, content, size)) {
            fsync(fd);
            ok = true;
            if (patch_count != nullptr) {
                *patch_count = count;
            }
        } else if (count == 0) {
            ok = true;
        }
    }

    free(content);
    close(fd);
    return ok;
}

void prepareProtectedProcShims(const char *library_path) {
    if (!isProcShimEnabled()) {
        return;
    }
    if (!shouldPrepareProcVersionShim(library_path)) {
        return;
    }

    ProcShimContext context = {};
    if (!buildProcShimContext(library_path, &context)) {
        return;
    }

    int patch_count = 0;
    if (!patchProtectedProcStrings(library_path, &patch_count)) {
        return;
    }
    if (patch_count <= 0) {
        return;
    }
    if (!ensureProcShimFds(context)) {
        return;
    }
    gProcShimContext = context;
    gProcShimContextReady = true;

    ALOGD("protected proc shims prepared lib=%s patches=%d package=%s maps=%s cmdline=%s comm=%s meminfo=%s version=%s",
          library_path, patch_count, context.package_name, kProcMapsFdPath, kProcCmdlineFdPath,
          kProcCommFdPath, kProcMeminfoFdPath, kProcVersionFdPath);
}

}

extern "C" void refreshProtectedProcMapsShim() {
    if (!gProcShimContextReady || gRefreshingProcMapsShim) {
        return;
    }
    if (fcntl(kProcMapsFd, F_GETFD) == -1 && errno == EBADF) {
        return;
    }

    gRefreshingProcMapsShim = true;
    pthread_mutex_lock(&gProcMapsRefreshMutex);
    bool ok = refreshProcMapsShimAtomically(gProcShimContext);
    pthread_mutex_unlock(&gProcMapsRefreshMutex);
    if (!ok) {
        ALOGE("protected proc maps shim refresh failed errno=%d", errno);
        lseek(kProcMapsFd, 0, SEEK_SET);
    }
    gRefreshingProcMapsShim = false;
}

extern "C" bool isProtectedProcMapsShimReady() {
    return gProcShimContextReady && isProcShimEnabled();
}

HOOK_JNI(jstring, nativeLoad, JNIEnv *env, jobject obj, jstring name, jobject class_loader) {
    const char *nameC = env->GetStringUTFChars(name, JNI_FALSE);
    const char *loadPath = IO::redirectPath(nameC);
    prepareProtectedProcShims(loadPath);
    ALOGD("nativeLoad: %s redirected=%s", nameC, loadPath);
    jstring loadName = samePath(nameC, loadPath) ? name : env->NewStringUTF(loadPath);
    startProcMapsRefreshDuringNativeLoad();
    jstring result = orig_nativeLoad(env, obj, loadName, class_loader);
    refreshProtectedProcMapsShim();
    installNativeFileHooks();
    result = handleNativeLoadResult(env, result, nameC, loadPath);
    if (loadName != name) {
        env->DeleteLocalRef(loadName);
        free(const_cast<char *>(loadPath));
    }
    env->ReleaseStringUTFChars(name, nameC);
    return result;
}

HOOK_JNI(jstring, nativeLoad2, JNIEnv *env, jobject obj, jstring name, jobject class_loader,
         jobject caller) {
    const char *nameC = env->GetStringUTFChars(name, JNI_FALSE);
    const char *loadPath = IO::redirectPath(nameC);
    prepareProtectedProcShims(loadPath);
    ALOGD("nativeLoad: %s redirected=%s", nameC, loadPath);
    jstring loadName = samePath(nameC, loadPath) ? name : env->NewStringUTF(loadPath);
    startProcMapsRefreshDuringNativeLoad();
    jstring result = orig_nativeLoad2(env, obj, loadName, class_loader, caller);
    refreshProtectedProcMapsShim();
    installNativeFileHooks();
    result = handleNativeLoadResult(env, result, nameC, loadPath);
    if (loadName != name) {
        env->DeleteLocalRef(loadName);
        free(const_cast<char *>(loadPath));
    }
    env->ReleaseStringUTFChars(name, nameC);
    return result;
}

void RuntimeHook::init(JNIEnv *env) {
    const char *className = BB_CORE_STR("java/lang/Runtime");
    if (BoxCore::getApiLevel() >= __ANDROID_API_Q__) {
        JniHook::HookJniFun(env, className, BB_CORE_STR("nativeLoad"),
                            BB_CORE_STR("(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;"),
                            (void *) new_nativeLoad2,
                            (void **) (&orig_nativeLoad2), true);
    } else {
        JniHook::HookJniFun(env, className, BB_CORE_STR("nativeLoad"),
                            BB_CORE_STR("(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;"),
                            (void *) new_nativeLoad,
                            (void **) (&orig_nativeLoad), true);
    }
}
