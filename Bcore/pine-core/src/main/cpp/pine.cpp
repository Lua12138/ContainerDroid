//
// Created by canyie on 2020/2/9.
//

#include <cassert>
#include "pine_config.h"
#include "jni_bridge.h"
#include "android.h"
#include "art/art_method.h"
#include "utils/macros.h"
#include "utils/scoped_local_ref.h"
#include "utils/log.h"
#include "utils/jni_helper.h"
#include "trampoline/extras.h"
#include "utils/memory.h"
#include "utils/well_known_classes.h"
#include "trampoline/trampoline_installer.h"

using namespace pine;

static constexpr jint kArchArm = 1;
static constexpr jint kArchArm64 = 2;
static constexpr jint kArchX86 = 3;
static constexpr jint kCurrentArch =
#ifdef __aarch64__
        kArchArm64
#elif defined(__arm__)
        kArchArm
#elif defined(__i386__)
        kArchX86
#endif
        ;

bool PineConfig::debug = false;
bool PineConfig::debuggable = false;
bool PineConfig::anti_checks = false;
bool PineConfig::jit_compilation_allowed = true;

void Pine_init0(JNIEnv* env, jclass Pine, jint androidVersion, jboolean debug, jboolean debuggable,
        jboolean antiChecks, jboolean disableHiddenApiPolicy, jboolean disableHiddenApiPolicyForPlatformDomain) {
    PineConfig::debug = static_cast<bool>(debug);
    PineConfig::debuggable = static_cast<bool>(debuggable);
    PineConfig::anti_checks = static_cast<bool>(antiChecks);
    LOGI(PINE_STR("Pine native init..."));
    TrampolineInstaller::GetOrInitDefault(); // trigger TrampolineInstaller::default_ initialization
    Android::Init(env, androidVersion, disableHiddenApiPolicy, disableHiddenApiPolicyForPlatformDomain);
    {
        ScopedLocalClassRef Ruler(env, PINE_STR("top/canyie/pine/Ruler"));
        auto m1 = art::ArtMethod::Require(env, Ruler.Get(), PINE_STR("m1"), PINE_STR("()V"), true);
        auto m2 = art::ArtMethod::Require(env, Ruler.Get(), PINE_STR("m2"), PINE_STR("()V"), true);

        uint32_t expected_access_flags;
        do {
            ScopedLocalClassRef Method(env, PINE_STR("java/lang/reflect/Method"));
            jmethodID getAccessFlags = Method.FindMethodID(PINE_STR("getAccessFlags"), PINE_STR("()I"));
            if (LIKELY(getAccessFlags != nullptr)) {
                ScopedLocalRef javaM1(env, env->ToReflectedMethod(
                        Ruler.Get(), m1->ToMethodID(), JNI_TRUE));
                expected_access_flags = static_cast<uint32_t>(env->CallIntMethod(
                        javaM1.Get(), getAccessFlags));

                if (LIKELY(!env->ExceptionCheck())) break;

                LOGW("Method.getAccessFlags threw exception unexpectedly, use default access flags.");
                env->ExceptionDescribe();
                env->ExceptionClear();
            } else {
                LOGW("Method.getAccessFlags not found, use default access flags.");
            }
            expected_access_flags = AccessFlags::kPrivate | AccessFlags::kStatic | AccessFlags::kNative;
        } while (false);

        if (androidVersion >= Android::kQ) {
            expected_access_flags |= AccessFlags::kPublicApi;
        }

        ScopedLocalClassRef I(env, PINE_STR("top/canyie/pine/Ruler$I"));
        auto abstract_method = art::ArtMethod::Require(env, I.Get(), PINE_STR("m"), PINE_STR("()V"), false);
        art::ArtMethod::InitMembers(env, m1, m2, abstract_method, expected_access_flags);

        if (UNLIKELY(!art::ArtMethod::GetQuickToInterpreterBridge())) {
            // This is a workaround for art_quick_to_interpreter_bridge not found.
            // This case is almost impossible to enter
            // because its symbols are found almost always on all devices.
            // But if it happened... Try to get it with an abstract method (it is not compilable
            // and its entry is art_quick_to_interpreter_bridge)
            // Note: We DO NOT use platform's abstract methods
            // because their entry may not be interpreter entry.

            LOGE("art_quick_to_interpreter_bridge not found, try workaround");

            void* entry = abstract_method->GetEntryPointFromCompiledCode();
            LOGE("New art_quick_to_interpreter_bridge %p", entry);
            art::ArtMethod::SetQuickToInterpreterBridge(entry);
        }
    }

    env->SetStaticIntField(Pine,
                           env->GetStaticFieldID(Pine, PINE_STR("arch"), PINE_STR("I")),
                           kCurrentArch);
}

jobject Pine_hook0(JNIEnv* env, jclass, jlong threadAddress, jclass declaring, jobject javaTarget,
            jobject javaBridge, jboolean isInlineHook, jboolean isJni, jboolean isProxy) {
    auto thread = reinterpret_cast<art::Thread*>(threadAddress);
    auto target = art::ArtMethod::FromReflectedMethod(env, javaTarget);
    auto bridge = art::ArtMethod::FromReflectedMethod(env, javaBridge);

    if (PineConfig::jit_compilation_allowed) {
        // The bridge method entry will be hardcoded in the trampoline, subsequent optimization
        // operations that require modification of the bridge method entry will not take effect.
        // Try to do JIT compilation first to get the best performance.
        bridge->Compile(thread);
    }

    bool is_inline_hook = JBOOL_TRUE(isInlineHook);
    const bool is_native = JBOOL_TRUE(isJni);
    const bool is_proxy = JBOOL_TRUE(isProxy);
    const bool is_native_or_proxy = is_native || is_proxy;

    TrampolineInstaller* trampoline_installer = TrampolineInstaller::GetDefault();

    if (UNLIKELY(is_inline_hook && trampoline_installer->IsReplacementOnly())) {
        is_inline_hook = false;
    }

    if (UNLIKELY(is_inline_hook && trampoline_installer->CannotSafeInlineHook(target))) {
        LOGW("Cannot safe inline hook the target method, force replacement mode.");
        is_inline_hook = false;
    }

    bool skip_first_few_bytes = PineConfig::anti_checks
            && is_inline_hook && trampoline_installer->CanSkipFirstFewBytes(target);

    art::ArtMethod* backup;
    if (WellKnownClasses::java_lang_reflect_ArtMethod) {
        // If ArtMethod has mirror class in java, we cannot use malloc to direct
        // allocate a instance because it must has a record in Runtime.

        backup = static_cast<art::ArtMethod*>(thread->AllocNonMovable(
                WellKnownClasses::java_lang_reflect_ArtMethod));
        if (UNLIKELY(!backup)) {
            // On Android kitkat, moving gc is not supported in art. All objects are immovable.
            if (UNLIKELY(Android::version != Android::kK)) {
                LOGE("Failed to allocate an immovable object for creating backup method.");
                env->ExceptionClear();
            }

            jobject javaBackup = env->AllocObject(WellKnownClasses::java_lang_reflect_ArtMethod);
            if (UNLIKELY(env->ExceptionCheck())) {
                LOGE("Can't create the backup method!");
                return nullptr;
            }
            backup = static_cast<art::ArtMethod*>(thread->DecodeJObject(javaBackup));
        }
    } else {
        backup = art::ArtMethod::New();
        if (UNLIKELY(!backup)) {
            int local_errno = errno;
            LOGE("Cannot allocate backup ArtMethod, errno %d(%s)", errno, strerror(errno));
            if (local_errno == ENOMEM) {
                JNIHelper::Throw(env, PINE_STR("java/lang/OutOfMemoryError"),
                                 PINE_STR("No memory for allocate backup method"));
            } else {
                JNIHelper::Throw(env, PINE_STR("java/lang/RuntimeException"),
                                 PINE_STR("hook failed: cannot allocate backup method"));
            }
            return nullptr;
        }
    }

    bool success;
    char error_msg[288];

    {
        // An ArtMethod is a very important object. Many threads depend on their values,
        // so we need to suspend other threads to avoid errors when hooking.
        ScopedSuspendVM suspend_vm(thread);

        void* call_origin = is_inline_hook
                            ? trampoline_installer->InstallInlineTrampoline(target, bridge, skip_first_few_bytes)
                            : trampoline_installer->InstallReplacementTrampoline(target, bridge);

        if (LIKELY(call_origin)) {
            backup->BackupFrom(target, call_origin, is_inline_hook, is_native, is_proxy);
            target->AfterHook(is_inline_hook, is_native_or_proxy);
            success = true;
        } else {
            snprintf(error_msg, sizeof(error_msg),
                     PINE_STR("Failed to install %s trampoline on method %p: %s (%d)."),
                     is_inline_hook ? PINE_STR("inline") : PINE_STR("replacement"),
                     target, strerror(errno), errno);
            if (errno == EACCES || errno == EPERM)
                strlcat(error_msg,
                        PINE_STR(" This is a security failure, check selinux policy, seccomp or capabilities. Earlier log may point out root cause."),
                        sizeof(error_msg));
            LOGE("%s", error_msg);
            success = false;
        }
    }

    if (LIKELY(success)) {
        return env->ToReflectedMethod(declaring, backup->ToMethodID(),
                                      static_cast<jboolean>(backup->IsStatic()));
    } else {
        JNIHelper::Throw(env,
                         errno == EACCES || errno == EPERM
                         ? PINE_STR("java/lang/SecurityException")
                         : PINE_STR("java/lang/RuntimeException"),
                         error_msg);
        return nullptr;
    }
}

jlong Pine_getArtMethod(JNIEnv* env, jclass, jobject javaMethod) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(
            art::ArtMethod::FromReflectedMethod(env, javaMethod)));
}

jboolean Pine_compile0(JNIEnv* env, jclass, jlong thread, jobject javaMethod) {
    return static_cast<jboolean>(art::ArtMethod::FromReflectedMethod(env, javaMethod)->Compile(
            reinterpret_cast<art::Thread*>(thread)));
}

jboolean Pine_decompile0(JNIEnv* env, jclass, jobject javaMethod, jboolean disableJit) {
    return static_cast<jboolean>(art::ArtMethod::FromReflectedMethod(env, javaMethod)->Decompile(
            disableJit));
}

jboolean Pine_disableJitInline0(JNIEnv*, jclass) {
    return static_cast<jboolean>(art::Jit::DisableInline());
}

void Pine_setJitCompilationAllowed(JNIEnv*, jclass, jboolean allowed) {
    PineConfig::jit_compilation_allowed = allowed;
}

jboolean Pine_disableProfileSaver0(JNIEnv*, jclass) {
    return static_cast<jboolean>(Android::DisableProfileSaver());
}

jobject Pine_getObject0(JNIEnv* env, jclass, jlong thread, jlong address) {
    return reinterpret_cast<art::Thread*>(thread)->AddLocalRef(env, reinterpret_cast<Object*>(address));
}

jlong Pine_getAddress0(JNIEnv*, jclass, jlong thread, jobject o) {
    return reinterpret_cast<jlong>(reinterpret_cast<art::Thread*>(thread)->DecodeJObject(o));
}

#ifdef __aarch64__
void Pine_getArgsArm64(JNIEnv* env, jclass, jlong javaExtras, jlong sp, jbooleanArray typeWides,
        jlongArray coreRegisters, jlongArray stack, jdoubleArray fpRegisters) {
    auto extras = reinterpret_cast<Extras*>(javaExtras);
    jint total = env->GetArrayLength(typeWides);
    jint crLength = env->GetArrayLength(coreRegisters);
    jint stackLength = env->GetArrayLength(stack);

    if (LIKELY(total != 0)) {
        jboolean* wides = static_cast<jboolean*>(env->GetPrimitiveArrayCritical(typeWides, nullptr));
        if (LIKELY(crLength > 0)) {
            jlong* array = static_cast<jlong*>(env->GetPrimitiveArrayCritical(coreRegisters, nullptr));

            do {
                array[0] = reinterpret_cast<jlong>(extras->r1);
                if (crLength == 1) break;
                array[1] = reinterpret_cast<jlong>(extras->r2);
                if (crLength == 2) break;
                array[2] = reinterpret_cast<jlong>(extras->r3);
                if (crLength < 8) break; // x4-x7 will be restored in java
            } while (false);
            env->ReleasePrimitiveArrayCritical(coreRegisters, array, JNI_ABORT);
        }

        {
            // get args from stack
            uintptr_t current_on_stack = static_cast<uintptr_t>(sp + 8/*callee*/);

            jlong* array = static_cast<jlong*>(env->GetPrimitiveArrayCritical(stack, nullptr));
            for (int i = 0; i < stackLength; ++i) {
                array[i] = *reinterpret_cast<jlong*>(current_on_stack);
                current_on_stack += wides[i] == JNI_TRUE ? 8 : 4;
            }
            env->ReleasePrimitiveArrayCritical(stack, array, JNI_ABORT);
        }
        env->ReleasePrimitiveArrayCritical(typeWides, wides, 0);
    }

    // Restore floating point (double and float) arguments.
    // Note: In fact, we don’t need to restore them here,
    // but an unknown error will occur when receiving directly in the bridge method
    // See https://github.com/canyie/pine/issues/9
    jint fpArrayLength = env->GetArrayLength(fpRegisters);
    if (UNLIKELY(fpArrayLength != 0)) {
        env->SetDoubleArrayRegion(fpRegisters, 0, fpArrayLength, extras->fps);
    }
    delete extras;
}
#elif defined(__arm__)
void Pine_getArgsArm32(JNIEnv *env, jclass, jint javaExtras, jint sp,
                       jintArray crOut, jintArray stack, jfloatArray fpOut) {
    auto extras = reinterpret_cast<Extras*>(javaExtras);
    jint crLength = env->GetArrayLength(crOut);
    jint stackLength = env->GetArrayLength(stack);
    if (LIKELY(crLength != 0)) {
        jint* array = static_cast<jint*>(env->GetPrimitiveArrayCritical(crOut, nullptr));

#pragma clang diagnostic push
#pragma ide diagnostic ignored "OCSimplifyInspection"
        do {
            // Normal: use r1, r2, r3.
            array[0] = reinterpret_cast<jint>(extras->r1);
            if (crLength == 1) break;
            array[1] = reinterpret_cast<jint>(extras->r2);
            if (crLength == 2) break;
            array[2] = reinterpret_cast<jint>(extras->r3);
        } while (false);
#pragma clang diagnostic pop

        env->ReleasePrimitiveArrayCritical(crOut, array, JNI_ABORT);
    }

    if (LIKELY(stackLength != 0)) {
        // get args from stack
        env->SetIntArrayRegion(stack, 0, stackLength, reinterpret_cast<const jint*>(sp + 4 /*callee*/));
    }

    jint fpLength = env->GetArrayLength(fpOut);
    if (UNLIKELY(fpLength != 0)) {
        env->SetFloatArrayRegion(fpOut, 0, fpLength, extras->fps);
    }
    delete extras;
}
#elif defined(__i386__)
void Pine_getArgsX86(JNIEnv* env, jclass, jint javaExtras, jintArray javaArray, jint ebx) {
    auto extras = reinterpret_cast<Extras*>(javaExtras);
    jint length = env->GetArrayLength(javaArray);
    if (LIKELY(length > 0)) {
        jint* array = static_cast<jint*>(env->GetPrimitiveArrayCritical(javaArray, nullptr));
        if (UNLIKELY(!array)) {
            constexpr const char *error_msg = "GetPrimitiveArrayCritical returned nullptr! javaArray is invalid?";
            LOGF(error_msg);
            env->FatalError(error_msg);
            abort(); // Unreachable
        }

        do {
            array[0] = reinterpret_cast<jint>(extras->ecx);
            if (length == 1) break;
            array[1] = reinterpret_cast<jint>(extras->edx);
            if (length == 2) break;
            if (length == 3) {
                // sizeof(args) == 12: use ecx, edx and ebx.
                array[2] = ebx;
                break;
            }
            uintptr_t esp = reinterpret_cast<uintptr_t>(extras->esp) + 4/*edi*/;

            // get args from stack
            for (int i = 2; i < length; i++) {
                array[i] = *reinterpret_cast<jint*> (esp + 4 /*callee*/ + 4 * i);
            }
        } while (false);

        env->ReleasePrimitiveArrayCritical(javaArray, array, JNI_ABORT);
    }
//  extras->ReleaseLock();
}
#endif

void Pine_updateDeclaringClass(JNIEnv* env, jclass, jobject javaOrigin, jobject javaBackup) {
    auto origin = art::ArtMethod::FromReflectedMethod(env, javaOrigin);
    auto backup = art::ArtMethod::FromReflectedMethod(env, javaBackup);
    uint32_t declaring_class = origin->GetDeclaringClass();
    if (declaring_class != backup->GetDeclaringClass()) {
        LOGI("The declaring_class of method has moved by gc, update its reference in backup method.");
        backup->SetDeclaringClass(declaring_class);
    }
}

void Pine_setDebuggable(JNIEnv*, jclass, jboolean debuggable) {
    PineConfig::debuggable = static_cast<bool>(debuggable);
}

void Pine_disableHiddenApiPolicy0(JNIEnv*, jclass, jboolean application, jboolean platform) {
    Android::DisableHiddenApiPolicy(application, platform);
}

jlong Pine_currentArtThread0(JNIEnv* env, jclass) {
    return reinterpret_cast<jlong>(art::Thread::Current(env));
}

void Pine_makeClassesVisiblyInitialized(JNIEnv*, jclass, jlong thread) {
    Android::MakeInitializedClassesVisiblyInitialized(reinterpret_cast<void*>(thread), true);
}

jlong Pine_cloneExtras(JNIEnv*, jclass, jlong extras) {
    return reinterpret_cast<jlong>(reinterpret_cast<Extras*>(extras)->CloneAndUnlock());
}

void Pine_enableFastNative(JNIEnv* env, jclass Pine) {
    LOGI("Experimental feature FastNative is enabled.");
    const struct {
        const char* name;
        const char* signature;
    } fast_native_methods[] = {
            {PINE_STR("getArtMethod"), PINE_STR("(Ljava/lang/reflect/Member;)J")},
            {PINE_STR("updateDeclaringClass"), PINE_STR("(Ljava/lang/reflect/Member;Ljava/lang/reflect/Method;)V")},
            {PINE_STR("decompile0"), PINE_STR("(Ljava/lang/reflect/Member;Z)Z")},
            {PINE_STR("disableJitInline0"), PINE_STR("()Z")},
            {PINE_STR("setJitCompilationAllowed0"), PINE_STR("(Z)V")},
            {PINE_STR("disableProfileSaver0"), PINE_STR("()Z")},
            {PINE_STR("getObject0"), PINE_STR("(JJ)Ljava/lang/Object;")},
            {PINE_STR("getAddress0"), PINE_STR("(JLjava/lang/Object;)J")},
            {PINE_STR("setDebuggable0"), PINE_STR("(Z)V")},
            {PINE_STR("disableHiddenApiPolicy0"), PINE_STR("(ZZ)V")},
            {PINE_STR("currentArtThread0"), PINE_STR("()J")},
            {PINE_STR("cloneExtras"), PINE_STR("(J)J")},
#ifdef __aarch64__
            {PINE_STR("getArgsArm64"), PINE_STR("(JJ[Z[J[J[D)V")}
#elif defined(__arm__)
            {PINE_STR("getArgsArm32"), PINE_STR("(II[I[I[F)V")}
#elif defined(__i386__)
            {PINE_STR("getArgsX86"), PINE_STR("(I[II)V")}
#endif
    };
    for (auto& method_info : fast_native_methods) {
        auto method = art::ArtMethod::Require(env, Pine, method_info.name, method_info.signature, true);
        if (method != nullptr) {
            method->SetFastNative();
        }
    }
}

bool register_Pine(JNIEnv* env, jclass Pine) {
    JNINativeMethod methods[] = {
            {PINE_STR("init0"), PINE_STR("(IZZZZZ)V"), (void*) Pine_init0},
            {PINE_STR("enableFastNative"), PINE_STR("()V"), (void*) Pine_enableFastNative},
            {PINE_STR("getArtMethod"), PINE_STR("(Ljava/lang/reflect/Member;)J"), (void*) Pine_getArtMethod},
            {PINE_STR("hook0"), PINE_STR("(JLjava/lang/Class;Ljava/lang/reflect/Member;Ljava/lang/reflect/Method;ZZZ)Ljava/lang/reflect/Method;"), (void*) Pine_hook0},
            {PINE_STR("compile0"), PINE_STR("(JLjava/lang/reflect/Member;)Z"), (void*) Pine_compile0},
            {PINE_STR("decompile0"), PINE_STR("(Ljava/lang/reflect/Member;Z)Z"), (void*) Pine_decompile0},
            {PINE_STR("disableJitInline0"), PINE_STR("()Z"), (void*) Pine_disableJitInline0},
            {PINE_STR("setJitCompilationAllowed0"), PINE_STR("(Z)V"), (void*) Pine_setJitCompilationAllowed},
            {PINE_STR("disableProfileSaver0"), PINE_STR("()Z"), (void*) Pine_disableProfileSaver0},
            {PINE_STR("updateDeclaringClass"), PINE_STR("(Ljava/lang/reflect/Member;Ljava/lang/reflect/Method;)V"), (void*) Pine_updateDeclaringClass},
            {PINE_STR("getObject0"), PINE_STR("(JJ)Ljava/lang/Object;"), (void*) Pine_getObject0},
            {PINE_STR("getAddress0"), PINE_STR("(JLjava/lang/Object;)J"), (void*) Pine_getAddress0},
            {PINE_STR("setDebuggable0"), PINE_STR("(Z)V"), (void*) Pine_setDebuggable},
            {PINE_STR("disableHiddenApiPolicy0"), PINE_STR("(ZZ)V"), (void*) Pine_disableHiddenApiPolicy0},
            {PINE_STR("currentArtThread0"), PINE_STR("()J"), (void*) Pine_currentArtThread0},
            {PINE_STR("makeClassesVisiblyInitialized"), PINE_STR("(J)V"), (void*) Pine_makeClassesVisiblyInitialized},
            {PINE_STR("cloneExtras"), PINE_STR("(J)J"), (void*) Pine_cloneExtras},
#ifdef __aarch64__
            {PINE_STR("getArgsArm64"), PINE_STR("(JJ[Z[J[J[D)V"), (void*) Pine_getArgsArm64}
#elif defined(__arm__)
            {PINE_STR("getArgsArm32"), PINE_STR("(II[I[I[F)V"), (void*) Pine_getArgsArm32}
#elif defined(__i386__)
            {PINE_STR("getArgsX86"), PINE_STR("(I[II)V"), (void*) Pine_getArgsX86}
#endif
    };
    return LIKELY(env->RegisterNatives(Pine, methods, NELEM(methods)) == JNI_OK);
}

EXPORT_C void bbp_av0(int version) {
    Android::version = version;
}

EXPORT_C void* bbp_oe0(const char* elf) {
    return new ElfImg(elf);
}

EXPORT_C void bbp_ce0(void* handle) {
    delete static_cast<ElfImg*>(handle);
}

EXPORT_C void* bbp_es0(void* handle, const char* symbol) {
    return static_cast<ElfImg*>(handle)->GetSymbolAddress(symbol);
}

EXPORT_C bool bbp_sh0(const char* elf, const char* symbol, void* replace) {
    ElfImg handle(elf);
    void* addr = handle.GetSymbolAddress(symbol);
    if (UNLIKELY(!addr)) return false;
    return TrampolineInstaller::GetOrInitDefault()->NativeHookNoBackup(addr, replace);
}

EXPORT_C void bbp_ih0(void* target, void* replace) {
    TrampolineInstaller::GetOrInitDefault()->NativeHookNoBackup(target, replace);
}

EXPORT_C void bbp_np0(void* target, size_t size) {
    TrampolineInstaller::GetOrInitDefault()->FillWithNop(target, size);
}
