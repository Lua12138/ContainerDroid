//
// Created by Milk on 4/25/21.
//

#include "BinderHook.h"
#include <IO.h>
#include <BoxCore.h>
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"

#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#if !defined(__LP64__) && !defined(BINDER_IPC_32BIT)
#define BINDER_IPC_32BIT
#endif
#include <linux/android/binder.h>

#if defined(__LP64__)
#define BB_ELF_R_SYM ELF64_R_SYM
#else
#define BB_ELF_R_SYM ELF32_R_SYM
#endif

namespace {
using IoctlFn = int (*)(int, unsigned long, ...);
using BpBinderTransactFn = int32_t (*)(void *, uint32_t, const void *, void *, uint32_t);
using ParcelDataFn = const uint8_t *(*)(const void *);
using ParcelDataSizeFn = size_t (*)(const void *);
using SymbolMatcher = bool (*)(const char *);

static IoctlFn orig_ioctl = nullptr;
static BpBinderTransactFn orig_bpbinder_transact = nullptr;
static ParcelDataFn gParcelData = nullptr;
static ParcelDataSizeFn gParcelDataSize = nullptr;
static bool gRecordNative = false;
static bool gRecordIoctl = false;
static bool gIoctlHookInstalled = false;
static bool gBpBinderTransactHookAttempted = false;
static bool gBpBinderTransactHookInstalled = false;
static __thread bool gInIoctlHook = false;
static __thread bool gInBpBinderHook = false;

static jclass gBinderMonitorClass = nullptr;
static jmethodID gRecordIoctlMethod = nullptr;
static jmethodID gRecordIoctlLegacyMethod = nullptr;
static jmethodID gRecordNativeMethod = nullptr;

static const char *BPBINDER_TRANSACT_SYMBOL = "_ZN7android8BpBinder8transactEjRKNS_6ParcelEPS1_j";
static const char *PARCEL_DATA_SYMBOL = "_ZNK7android6Parcel4dataEv";
static const char *PARCEL_DATA_SIZE_SYMBOL = "_ZNK7android6Parcel8dataSizeEv";
static const char *SOURCE_NATIVE_BPBINDER_TRANSACT = "Native.BpBinder.transact";

struct PatchSpec {
    SymbolMatcher matcher;
    void *replacement;
    void **original;
    bool skipLibc;
    int patched;
};

static int new_ioctl(int fd, unsigned long request, ...);
static int32_t new_bpbinder_transact(void *thiz, uint32_t code, const void *data,
                                     void *reply, uint32_t flags);

static uintptr_t dynamicPtr(uintptr_t base, uintptr_t value) {
    if (value == 0) {
        return 0;
    }
    if (base != 0 && value < base) {
        return base + value;
    }
    return value;
}

static bool makeWritable(void *address) {
    long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) {
        return false;
    }
    uintptr_t page = reinterpret_cast<uintptr_t>(address) & ~(static_cast<uintptr_t>(pageSize) - 1);
    return mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(pageSize),
                    PROT_READ | PROT_WRITE) == 0;
}

static bool isIoctlSymbol(const char *name) {
    return name != nullptr && (strcmp(name, "ioctl") == 0 || strcmp(name, "__ioctl") == 0);
}

static bool isBpBinderTransactSymbol(const char *name) {
    return name != nullptr && strcmp(name, BPBINDER_TRANSACT_SYMBOL) == 0;
}

static void patchSlot(uintptr_t *slot, PatchSpec *spec) {
    if (slot == nullptr || spec == nullptr || spec->replacement == nullptr
            || *slot == reinterpret_cast<uintptr_t>(spec->replacement)) {
        return;
    }
    if (spec->original != nullptr && *spec->original == nullptr && *slot != 0) {
        *spec->original = reinterpret_cast<void *>(*slot);
    }
    if (!makeWritable(slot)) {
        return;
    }
    *slot = reinterpret_cast<uintptr_t>(spec->replacement);
    __builtin___clear_cache(reinterpret_cast<char *>(slot),
                            reinterpret_cast<char *>(slot + 1));
    ++spec->patched;
}

static void patchRela(uintptr_t base, ElfW(Rela) *relocations, size_t size,
                      ElfW(Sym) *symtab, const char *strtab, PatchSpec *spec) {
    if (relocations == nullptr || symtab == nullptr || strtab == nullptr
            || spec == nullptr || spec->matcher == nullptr) {
        return;
    }
    size_t count = size / sizeof(ElfW(Rela));
    for (size_t i = 0; i < count; ++i) {
        ElfW(Rela) *rel = relocations + i;
        const char *name = strtab + symtab[BB_ELF_R_SYM(rel->r_info)].st_name;
        if (spec->matcher(name)) {
            patchSlot(reinterpret_cast<uintptr_t *>(base + rel->r_offset), spec);
        }
    }
}

static void patchRel(uintptr_t base, ElfW(Rel) *relocations, size_t size,
                     ElfW(Sym) *symtab, const char *strtab, PatchSpec *spec) {
    if (relocations == nullptr || symtab == nullptr || strtab == nullptr
            || spec == nullptr || spec->matcher == nullptr) {
        return;
    }
    size_t count = size / sizeof(ElfW(Rel));
    for (size_t i = 0; i < count; ++i) {
        ElfW(Rel) *rel = relocations + i;
        const char *name = strtab + symtab[BB_ELF_R_SYM(rel->r_info)].st_name;
        if (spec->matcher(name)) {
            patchSlot(reinterpret_cast<uintptr_t *>(base + rel->r_offset), spec);
        }
    }
}

static int patchLoadedObject(struct dl_phdr_info *info, size_t, void *data) {
    if (info == nullptr || info->dlpi_phdr == nullptr) {
        return 0;
    }
    PatchSpec *spec = reinterpret_cast<PatchSpec *>(data);
    if (spec == nullptr) {
        return 0;
    }
    const char *name = info->dlpi_name == nullptr ? "" : info->dlpi_name;
    if (strstr(name, BB_CORE_STR("libblackbox.so")) != nullptr
            || (spec->skipLibc && strstr(name, BB_CORE_STR("/libc.so")) != nullptr)) {
        return 0;
    }

    uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    ElfW(Dyn) *dynamic = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<ElfW(Dyn) *>(base + phdr.p_vaddr);
            break;
        }
    }
    if (dynamic == nullptr) {
        return 0;
    }

    ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    ElfW(Rela) *rela = nullptr;
    ElfW(Rela) *pltRela = nullptr;
    ElfW(Rel) *rel = nullptr;
    ElfW(Rel) *pltRel = nullptr;
    size_t relaSize = 0;
    size_t pltRelaSize = 0;
    size_t relSize = 0;
    size_t pltRelSize = 0;
    int pltType = DT_REL;

    for (ElfW(Dyn) *dyn = dynamic; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<ElfW(Sym) *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                break;
            case DT_RELA:
                rela = reinterpret_cast<ElfW(Rela) *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                break;
            case DT_RELASZ:
                relaSize = static_cast<size_t>(dyn->d_un.d_val);
                break;
            case DT_REL:
                rel = reinterpret_cast<ElfW(Rel) *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                break;
            case DT_RELSZ:
                relSize = static_cast<size_t>(dyn->d_un.d_val);
                break;
            case DT_JMPREL:
                pltRela = reinterpret_cast<ElfW(Rela) *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                pltRel = reinterpret_cast<ElfW(Rel) *>(pltRela);
                break;
            case DT_PLTRELSZ:
                pltRelaSize = static_cast<size_t>(dyn->d_un.d_val);
                pltRelSize = static_cast<size_t>(dyn->d_un.d_val);
                break;
            case DT_PLTREL:
                pltType = static_cast<int>(dyn->d_un.d_val);
                break;
            default:
                break;
        }
    }

    if (pltType == DT_RELA) {
        patchRela(base, pltRela, pltRelaSize, symtab, strtab, spec);
    } else {
        patchRel(base, pltRel, pltRelSize, symtab, strtab, spec);
    }
    patchRela(base, rela, relaSize, symtab, strtab, spec);
    patchRel(base, rel, relSize, symtab, strtab, spec);
    return 0;
}

static bool readU32(uint8_t *cursor, uint8_t *end, uint32_t *value) {
    if (cursor == nullptr || value == nullptr || cursor + sizeof(uint32_t) > end) {
        return false;
    }
    memcpy(value, cursor, sizeof(uint32_t));
    return true;
}

static bool decodeString16At(const uint8_t *buffer, size_t size, size_t offset,
                             char *out, size_t outSize) {
    if (buffer == nullptr || out == nullptr || outSize == 0 || offset + sizeof(int32_t) > size) {
        return false;
    }
    int32_t len = 0;
    memcpy(&len, buffer + offset, sizeof(len));
    if (len <= 0 || len > 240) {
        return false;
    }
    size_t bytes = static_cast<size_t>(len) * sizeof(uint16_t);
    if (offset + sizeof(int32_t) + bytes > size || static_cast<size_t>(len) + 1 > outSize) {
        return false;
    }
    bool hasDot = false;
    for (int32_t i = 0; i < len; ++i) {
        uint16_t ch = 0;
        memcpy(&ch, buffer + offset + sizeof(int32_t) + static_cast<size_t>(i) * 2, sizeof(ch));
        if (ch < 0x20 || ch > 0x7e) {
            return false;
        }
        char c = static_cast<char>(ch);
        if (!(c == '.' || c == '_' || c == '$' || (c >= '0' && c <= '9')
                || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
            return false;
        }
        if (c == '.') {
            hasDot = true;
        }
        out[i] = c;
    }
    out[len] = '\0';
    if (!hasDot) {
        return false;
    }
    return strncmp(out, "android.", 8) == 0
           || strncmp(out, "com.", 4) == 0
           || strncmp(out, "vendor.", 7) == 0
           || strstr(out, ".I") != nullptr;
}

static void extractInterfaceDescriptorFromBuffer(const uint8_t *buffer, size_t size,
                                                char *descriptor, size_t descriptorSize) {
    if (descriptor == nullptr || descriptorSize == 0) {
        return;
    }
    descriptor[0] = '\0';
    if (buffer == nullptr || size < sizeof(int32_t)) {
        return;
    }
    size_t scanSize = size < 512 ? size : 512;
    for (size_t offset = 0; offset + sizeof(int32_t) < scanSize; offset += sizeof(uint32_t)) {
        if (decodeString16At(buffer, scanSize, offset, descriptor, descriptorSize)) {
            return;
        }
    }
    descriptor[0] = '\0';
}

static void extractInterfaceDescriptor(const binder_transaction_data &transaction,
                                       char *descriptor, size_t descriptorSize) {
    if (descriptor == nullptr || descriptorSize == 0) {
        return;
    }
    descriptor[0] = '\0';
    const uint8_t *buffer = reinterpret_cast<const uint8_t *>(
            static_cast<uintptr_t>(transaction.data.ptr.buffer));
    size_t size = static_cast<size_t>(transaction.data_size);
    extractInterfaceDescriptorFromBuffer(buffer, size, descriptor, descriptorSize);
}

static void callJavaRecordIoctl(const char *descriptor, int code, int flags,
                                int dataSize, bool replyExpected, int handle,
                                const char *driverCommand) {
    if (gBinderMonitorClass == nullptr
            || (gRecordIoctlMethod == nullptr && gRecordIoctlLegacyMethod == nullptr
                    && gRecordNativeMethod == nullptr)) {
        return;
    }
    JavaVM *vm = BoxCore::getJavaVM();
    if (vm == nullptr) {
        return;
    }
    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    jstring javaDescriptor = nullptr;
    if (descriptor != nullptr && descriptor[0] != '\0') {
        javaDescriptor = env->NewStringUTF(descriptor);
    }
    if (gRecordIoctl && gRecordIoctlMethod != nullptr) {
        jstring javaDriverCommand = nullptr;
        if (driverCommand != nullptr && driverCommand[0] != '\0') {
            javaDriverCommand = env->NewStringUTF(driverCommand);
        }
        env->CallStaticVoidMethod(gBinderMonitorClass, gRecordIoctlMethod, javaDescriptor,
                                  static_cast<jint>(code), static_cast<jint>(flags),
                                  static_cast<jint>(dataSize),
                                  static_cast<jboolean>(replyExpected),
                                  static_cast<jint>(handle),
                                  javaDriverCommand);
        if (javaDriverCommand != nullptr) {
            env->DeleteLocalRef(javaDriverCommand);
        }
    } else if (gRecordIoctl && gRecordIoctlLegacyMethod != nullptr) {
        env->CallStaticVoidMethod(gBinderMonitorClass, gRecordIoctlLegacyMethod, javaDescriptor,
                                  static_cast<jint>(code), static_cast<jint>(flags),
                                  static_cast<jint>(dataSize),
                                  static_cast<jboolean>(replyExpected));
    } else if (gRecordNative && gRecordNativeMethod != nullptr) {
        jstring source = env->NewStringUTF("Native.ioctl.BINDER_WRITE_READ");
        env->CallStaticVoidMethod(gBinderMonitorClass, gRecordNativeMethod, javaDescriptor,
                                  static_cast<jint>(code), static_cast<jint>(flags),
                                  static_cast<jint>(dataSize),
                                  static_cast<jboolean>(replyExpected),
                                  source);
        if (source != nullptr) {
            env->DeleteLocalRef(source);
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (javaDescriptor != nullptr) {
        env->DeleteLocalRef(javaDescriptor);
    }
    if (attached) {
        vm->DetachCurrentThread();
    }
}

static void callJavaRecordNative(const char *descriptor, int code, int flags,
                                 int dataSize, bool replyExpected, const char *source) {
    if (gBinderMonitorClass == nullptr || gRecordNativeMethod == nullptr) {
        return;
    }
    JavaVM *vm = BoxCore::getJavaVM();
    if (vm == nullptr) {
        return;
    }
    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    jstring javaDescriptor = nullptr;
    if (descriptor != nullptr && descriptor[0] != '\0') {
        javaDescriptor = env->NewStringUTF(descriptor);
    }
    jstring javaSource = env->NewStringUTF(source == nullptr
                                           ? SOURCE_NATIVE_BPBINDER_TRANSACT
                                           : source);
    env->CallStaticVoidMethod(gBinderMonitorClass, gRecordNativeMethod, javaDescriptor,
                              static_cast<jint>(code), static_cast<jint>(flags),
                              static_cast<jint>(dataSize),
                              static_cast<jboolean>(replyExpected),
                              javaSource);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (javaSource != nullptr) {
        env->DeleteLocalRef(javaSource);
    }
    if (javaDescriptor != nullptr) {
        env->DeleteLocalRef(javaDescriptor);
    }
    if (attached) {
        vm->DetachCurrentThread();
    }
}

static void *findLibBinderSymbol(const char *symbol) {
    if (symbol == nullptr) {
        return nullptr;
    }
    void *address = dlsym(RTLD_DEFAULT, symbol);
    if (address != nullptr) {
        return address;
    }
    static void *libbinder = nullptr;
    if (libbinder == nullptr) {
        libbinder = dlopen("libbinder.so", RTLD_NOW);
    }
    return libbinder == nullptr ? nullptr : dlsym(libbinder, symbol);
}

static void resolveParcelSymbols() {
    if (gParcelData == nullptr) {
        gParcelData = reinterpret_cast<ParcelDataFn>(findLibBinderSymbol(PARCEL_DATA_SYMBOL));
    }
    if (gParcelDataSize == nullptr) {
        gParcelDataSize = reinterpret_cast<ParcelDataSizeFn>(
                findLibBinderSymbol(PARCEL_DATA_SIZE_SYMBOL));
    }
}

static void resolveBpBinderTransactSymbol() {
    if (orig_bpbinder_transact == nullptr) {
        orig_bpbinder_transact = reinterpret_cast<BpBinderTransactFn>(
                findLibBinderSymbol(BPBINDER_TRANSACT_SYMBOL));
    }
}

static int parcelDataSizeAsInt(size_t dataSize) {
    return dataSize > static_cast<size_t>(0x7fffffff)
           ? 0x7fffffff
           : static_cast<int>(dataSize);
}

static void recordNativeBpBinderTransact(uint32_t code, const void *data, void *reply,
                                         uint32_t flags) {
    if (!gRecordNative) {
        return;
    }
    resolveParcelSymbols();
    char descriptor[256];
    descriptor[0] = '\0';
    int dataSize = -1;
    if (data != nullptr && gParcelDataSize != nullptr) {
        size_t parcelSize = gParcelDataSize(data);
        dataSize = parcelDataSizeAsInt(parcelSize);
        if (gParcelData != nullptr) {
            const uint8_t *buffer = gParcelData(data);
            extractInterfaceDescriptorFromBuffer(buffer, parcelSize, descriptor,
                                                 sizeof(descriptor));
        }
    }
    callJavaRecordNative(descriptor, static_cast<int>(code), static_cast<int>(flags),
                         dataSize, reply != nullptr, SOURCE_NATIVE_BPBINDER_TRANSACT);
}

static const char *driverCommandName(uint32_t cmd) {
    switch (cmd) {
        case BC_TRANSACTION:
            return "BC_TRANSACTION";
        case BC_REPLY:
            return "BC_REPLY";
        case BC_TRANSACTION_SG:
            return "BC_TRANSACTION_SG";
        case BC_REPLY_SG:
            return "BC_REPLY_SG";
        case BR_TRANSACTION:
            return "BR_TRANSACTION";
        case BR_REPLY:
            return "BR_REPLY";
        default:
            return "UNKNOWN";
    }
}

static int transactionHandle(uint32_t cmd, const binder_transaction_data &transaction) {
    if (cmd == BC_TRANSACTION || cmd == BC_TRANSACTION_SG) {
        return static_cast<int>(transaction.target.handle);
    }
    return -1;
}

static bool transactionReplyExpected(uint32_t cmd, const binder_transaction_data &transaction) {
    if (cmd == BC_TRANSACTION || cmd == BC_TRANSACTION_SG) {
        return true;
    }
    if (cmd == BR_TRANSACTION) {
        return (transaction.flags & TF_ONE_WAY) == 0;
    }
    return false;
}

static void recordTransaction(const binder_transaction_data &transaction, uint32_t cmd) {
    char descriptor[256];
    extractInterfaceDescriptor(transaction, descriptor, sizeof(descriptor));
    callJavaRecordIoctl(descriptor, static_cast<int>(transaction.code),
                        static_cast<int>(transaction.flags),
                        static_cast<int>(transaction.data_size),
                        transactionReplyExpected(cmd, transaction),
                        transactionHandle(cmd, transaction),
                        driverCommandName(cmd));
}

static bool skipPayload(uint32_t cmd, uint8_t **cursor, uint8_t *end) {
    size_t size = 0;
    switch (cmd) {
        case BC_TRANSACTION:
        case BC_REPLY:
            size = sizeof(binder_transaction_data);
            break;
        case BC_TRANSACTION_SG:
        case BC_REPLY_SG:
            size = sizeof(binder_transaction_data_sg);
            break;
        case BC_FREE_BUFFER:
        case BC_DEAD_BINDER_DONE:
            size = sizeof(binder_uintptr_t);
            break;
        case BC_INCREFS:
        case BC_ACQUIRE:
        case BC_RELEASE:
        case BC_DECREFS:
            size = sizeof(uint32_t);
            break;
        case BC_INCREFS_DONE:
        case BC_ACQUIRE_DONE:
            size = sizeof(binder_ptr_cookie);
            break;
        case BC_REQUEST_DEATH_NOTIFICATION:
        case BC_CLEAR_DEATH_NOTIFICATION:
            size = sizeof(binder_handle_cookie);
            break;
        case BC_ACQUIRE_RESULT:
            size = sizeof(int32_t);
            break;
        case BC_ATTEMPT_ACQUIRE:
            size = sizeof(binder_pri_desc);
            break;
        case BC_REGISTER_LOOPER:
        case BC_ENTER_LOOPER:
        case BC_EXIT_LOOPER:
            size = 0;
            break;
        default:
            return false;
    }
    if (*cursor + size > end) {
        return false;
    }
    *cursor += size;
    return true;
}

static void inspectTransaction(uint32_t cmd, uint8_t **cursor, uint8_t *end) {
    if (cmd == BC_TRANSACTION || cmd == BC_REPLY) {
        if (*cursor + sizeof(binder_transaction_data) > end) {
            return;
        }
        binder_transaction_data transaction;
        memcpy(&transaction, *cursor, sizeof(transaction));
        recordTransaction(transaction, cmd);
        *cursor += sizeof(transaction);
        return;
    }
    if (cmd == BC_TRANSACTION_SG || cmd == BC_REPLY_SG) {
        if (*cursor + sizeof(binder_transaction_data_sg) > end) {
            return;
        }
        binder_transaction_data_sg transactionSg;
        memcpy(&transactionSg, *cursor, sizeof(transactionSg));
        recordTransaction(transactionSg.transaction_data, cmd);
        *cursor += sizeof(transactionSg);
    }
}

static bool skipReturnPayload(uint32_t cmd, uint8_t **cursor, uint8_t *end) {
    size_t size = 0;
    switch (cmd) {
        case BR_ERROR:
        case BR_ACQUIRE_RESULT:
            size = sizeof(int32_t);
            break;
        case BR_TRANSACTION:
        case BR_REPLY:
            size = sizeof(binder_transaction_data);
            break;
        case BR_TRANSACTION_SEC_CTX:
            size = sizeof(binder_transaction_data_secctx);
            break;
        case BR_INCREFS:
        case BR_ACQUIRE:
        case BR_RELEASE:
        case BR_DECREFS:
            size = sizeof(binder_ptr_cookie);
            break;
        case BR_ATTEMPT_ACQUIRE:
            size = sizeof(binder_pri_ptr_cookie);
            break;
        case BR_DEAD_BINDER:
        case BR_CLEAR_DEATH_NOTIFICATION_DONE:
            size = sizeof(binder_uintptr_t);
            break;
        case BR_OK:
        case BR_DEAD_REPLY:
        case BR_TRANSACTION_COMPLETE:
        case BR_NOOP:
        case BR_SPAWN_LOOPER:
        case BR_FINISHED:
        case BR_FAILED_REPLY:
            size = 0;
            break;
        default:
            return false;
    }
    if (*cursor + size > end) {
        return false;
    }
    *cursor += size;
    return true;
}

static void inspectBinderReadBuffer(void *arg) {
    if ((!gRecordIoctl && !gRecordNative) || arg == nullptr) {
        return;
    }
    binder_write_read *writeRead = reinterpret_cast<binder_write_read *>(arg);
    size_t size = static_cast<size_t>(writeRead->read_consumed);
    uint8_t *cursor = reinterpret_cast<uint8_t *>(
            static_cast<uintptr_t>(writeRead->read_buffer));
    if (cursor == nullptr || size < sizeof(uint32_t)) {
        return;
    }
    uint8_t *end = cursor + size;
    while (cursor + sizeof(uint32_t) <= end) {
        uint32_t cmd = 0;
        if (!readU32(cursor, end, &cmd)) {
            return;
        }
        cursor += sizeof(uint32_t);
        if (cmd == BR_TRANSACTION || cmd == BR_REPLY) {
            if (cursor + sizeof(binder_transaction_data) > end) {
                return;
            }
            binder_transaction_data transaction;
            memcpy(&transaction, cursor, sizeof(transaction));
            recordTransaction(transaction, cmd);
            cursor += sizeof(transaction);
            continue;
        }
        if (cmd == BR_TRANSACTION_SEC_CTX) {
            if (cursor + sizeof(binder_transaction_data_secctx) > end) {
                return;
            }
            binder_transaction_data_secctx transaction;
            memcpy(&transaction, cursor, sizeof(transaction));
            recordTransaction(transaction.transaction_data, BR_TRANSACTION);
            cursor += sizeof(transaction);
            continue;
        }
        if (!skipReturnPayload(cmd, &cursor, end)) {
            return;
        }
    }
}

static void inspectBinderWriteRead(void *arg) {
    if ((!gRecordIoctl && !gRecordNative) || arg == nullptr) {
        return;
    }
    binder_write_read *writeRead = reinterpret_cast<binder_write_read *>(arg);
    uint8_t *cursor = reinterpret_cast<uint8_t *>(
            static_cast<uintptr_t>(writeRead->write_buffer));
    size_t size = static_cast<size_t>(writeRead->write_size);
    if (cursor == nullptr || size < sizeof(uint32_t)) {
        return;
    }
    uint8_t *end = cursor + size;
    while (cursor + sizeof(uint32_t) <= end) {
        uint32_t cmd = 0;
        if (!readU32(cursor, end, &cmd)) {
            return;
        }
        cursor += sizeof(uint32_t);
        if (cmd == BC_TRANSACTION || cmd == BC_REPLY
                || cmd == BC_TRANSACTION_SG || cmd == BC_REPLY_SG) {
            inspectTransaction(cmd, &cursor, end);
            continue;
        }
        if (!skipPayload(cmd, &cursor, end)) {
            return;
        }
    }
}

static int new_ioctl(int fd, unsigned long request, ...) {
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    if (orig_ioctl == nullptr) {
        orig_ioctl = reinterpret_cast<IoctlFn>(dlsym(RTLD_NEXT, "ioctl"));
    }
    if (orig_ioctl == nullptr) {
        return -1;
    }
    if (!gInIoctlHook && request == BINDER_WRITE_READ) {
        gInIoctlHook = true;
        inspectBinderWriteRead(arg);
        gInIoctlHook = false;
    }
    int result = orig_ioctl(fd, request, arg);
    if (!gInIoctlHook && request == BINDER_WRITE_READ && result >= 0) {
        gInIoctlHook = true;
        inspectBinderReadBuffer(arg);
        gInIoctlHook = false;
    }
    return result;
}

static int32_t new_bpbinder_transact(void *thiz, uint32_t code, const void *data,
                                     void *reply, uint32_t flags) {
    if (orig_bpbinder_transact == nullptr) {
        resolveBpBinderTransactSymbol();
    }
    if (!gInBpBinderHook && gRecordNative) {
        gInBpBinderHook = true;
        recordNativeBpBinderTransact(code, data, reply, flags);
        gInBpBinderHook = false;
    }
    if (orig_bpbinder_transact == nullptr) {
        return -1;
    }
    return orig_bpbinder_transact(thiz, code, data, reply, flags);
}

static void installIoctlHook() {
    if (gIoctlHookInstalled) {
        return;
    }
    orig_ioctl = reinterpret_cast<IoctlFn>(dlsym(RTLD_NEXT, "ioctl"));
    PatchSpec spec = {
            isIoctlSymbol,
            reinterpret_cast<void *>(new_ioctl),
            reinterpret_cast<void **>(&orig_ioctl),
            true,
            0
    };
    dl_iterate_phdr(patchLoadedObject, &spec);
    gIoctlHookInstalled = spec.patched > 0;
    ALOGD("binder ioctl hook patched=%d", spec.patched);
}

static void installBpBinderTransactHook() {
    if (gBpBinderTransactHookAttempted) {
        return;
    }
    gBpBinderTransactHookAttempted = true;
    resolveBpBinderTransactSymbol();
    PatchSpec spec = {
            isBpBinderTransactSymbol,
            reinterpret_cast<void *>(new_bpbinder_transact),
            reinterpret_cast<void **>(&orig_bpbinder_transact),
            false,
            0
    };
    dl_iterate_phdr(patchLoadedObject, &spec);
    gBpBinderTransactHookInstalled = spec.patched > 0;
    ALOGD("BpBinder::transact hook patched=%d target=%p",
          spec.patched, reinterpret_cast<void *>(orig_bpbinder_transact));
}

static void initBinderMonitorBridge(JNIEnv *env) {
    if (env == nullptr || gBinderMonitorClass != nullptr) {
        return;
    }
    jclass clazz = env->FindClass(BB_CORE_STR("top/niunaijun/blackbox/binder/BlackBoxBinderMonitor"));
    if (clazz == nullptr) {
        env->ExceptionClear();
        return;
    }
    gBinderMonitorClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    env->DeleteLocalRef(clazz);
    gRecordIoctlMethod = env->GetStaticMethodID(gBinderMonitorClass,
                                                BB_CORE_STR("recordIoctlBinderTransaction"),
                                                BB_CORE_STR("(Ljava/lang/String;IIIZILjava/lang/String;)V"));
    if (gRecordIoctlMethod == nullptr) {
        env->ExceptionClear();
    }
    gRecordIoctlLegacyMethod = env->GetStaticMethodID(gBinderMonitorClass,
                                                      BB_CORE_STR("recordIoctlBinderTransaction"),
                                                      BB_CORE_STR("(Ljava/lang/String;IIIZ)V"));
    if (gRecordIoctlLegacyMethod == nullptr) {
        env->ExceptionClear();
    }
    gRecordNativeMethod = env->GetStaticMethodID(gBinderMonitorClass,
                                                 BB_CORE_STR("recordNativeBinderTransact"),
                                                 BB_CORE_STR("(Ljava/lang/String;IIIZLjava/lang/String;)V"));
    if (gRecordNativeMethod == nullptr) {
        env->ExceptionClear();
    }
    if (gRecordIoctlMethod == nullptr && gRecordIoctlLegacyMethod == nullptr
            && gRecordNativeMethod == nullptr) {
        gBinderMonitorClass = nullptr;
    }
}
}



HOOK_JNI(jint, getCallingUid, JNIEnv *env, jobject obj) {
    int orig = orig_getCallingUid(env, obj);
    return BoxCore::getCallingUid(env, orig);
}


void BinderHook::init(JNIEnv *env) {
    initBinderMonitorBridge(env);
    const char *clazz = "android/os/Binder";
    JniHook::HookJniFun(env, clazz, "getCallingUid", "()I", (void *) new_getCallingUid,
                        (void **) (&orig_getCallingUid), true);
}

void BinderHook::configureBinderMonitor(bool recordNative, bool recordIoctl) {
    gRecordNative = recordNative;
    gRecordIoctl = recordIoctl;
    if (gRecordNative) {
        installBpBinderTransactHook();
    }
    if (gRecordIoctl) {
        installIoctlHook();
    }
}
