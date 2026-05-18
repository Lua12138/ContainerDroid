package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;
import static top.niunaijun.blackbox.core.SourceAssertions.sliceBetween;

public class RuntimeHookSourceTest {

    @Test
    public void nativeHookInstallsRuntimeNativeLoadHook() throws Exception {
        String source = readSource(
                "src/main/cpp/BoxCore.cpp",
                "Bcore/src/main/cpp/BoxCore.cpp");

        boolean installed = false;
        for (String line : source.split("\\R")) {
            if ("RuntimeHook::init(env);".equals(line.trim())) {
                installed = true;
                break;
            }
        }
        assertTrue("BoxCore nativeHook should install Runtime.nativeLoad hook",
                installed);
    }

    @Test
    public void runtimeHookPreparesProtectedProcShimsBeforeNativeLoad() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        int prepare = source.indexOf("prepareProtectedProcShims(loadPath)");
        int load = source.indexOf("orig_nativeLoad");

        assertTrue("RuntimeHook should prepare protected-library proc shims",
                prepare >= 0);
        assertTrue("RuntimeHook should prepare shims before delegating to Runtime.nativeLoad",
                load > prepare);
        assertTrue("RuntimeHook should keep Android Q+ nativeLoad signature with caller Class",
                source.contains("(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;"));
        assertTrue("Proc shims should replace denied proc probes with stable fd paths",
                source.contains("/proc/version")
                        && source.contains("/proc/self/maps")
                        && source.contains("/proc/meminfo")
                        && source.contains("/proc/%d/cmdline")
                        && source.contains("/proc/%d/comm")
                        && source.contains("kProcVersionFdPath")
                        && source.contains("kProcMapsFdPath")
                        && source.contains("kProcMeminfoFdPath")
                        && source.contains("kProcCmdlineFdPath")
                        && source.contains("kProcCommFdPath"));
        assertTrue("Maps shim should hide host blackbox paths from protected native loaders",
                source.contains("writeFakeMapsFile")
                        && source.contains("/blackbox/data/user/")
                        && source.contains("/data/user/"));
    }

    @Test
    public void mapsShimDropsSandboxRuntimeAndPineHookMappings() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("Maps shim should classify sandbox runtime mappings",
                source.contains("shouldHideMapsLine"));
        assertTrue("Maps shim should drop host APK/dex mappings after package-name rewriting",
                source.contains("contains(line, kHostPackage)"));
        assertTrue("Maps shim should drop Pine native hook mappings",
                source.contains("libpine.so")
                        && source.contains("[anon:pine codes]"));
        assertTrue("Maps shim should preserve app-private mappings long enough to rewrite them",
                source.contains("shouldHideRawMapsLine(line, context)")
                        && source.contains("std::string sanitized = sanitizeMapsLine(line, context)")
                        && source.contains("if (shouldHideMapsLine(sanitized.c_str()))")
                        && source.contains("continue;"));
    }

    @Test
    public void mapsShimPreservesProtectedLibraryMappingAsPublicAppPath() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        int sanitize = source.indexOf("std::string sanitized = sanitizeMapsLine(line, context)");
        int hide = source.indexOf("shouldHideMapsLine(sanitized.c_str())");

        assertTrue("Maps shim should rewrite the protected library path before applying final hide rules",
                sanitize >= 0 && hide > sanitize);
        assertTrue("Maps shim should rewrite virtual app data root to public app data root",
                source.contains("replaceAll(&sanitized, context.virtual_data_root, context.public_data_root)"));
        assertTrue("Maps shim should not drop app-private protected-library lines just because they include the host package",
                source.contains("!isVirtualAppDataLine(line, context)"));
    }

    @Test
    public void mapsShimPreservesDataDataAliasProtectedLibraryMapping() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("Protected native mappings may appear through /data/data/<host>/blackbox even when Runtime.nativeLoad used /data/user/0; keep that alias as app-owned",
                source.contains("data_data_virtual_root")
                        && source.contains("isVirtualAppDataLine")
                        && source.contains("context.data_data_virtual_root"));
        assertTrue("Maps sanitizer should rewrite both /data/user and /data/data sandbox aliases to the public app root before final hide rules",
                source.contains("replaceAll(&sanitized, context.virtual_data_root, context.public_data_root)")
                        && source.contains("replaceAll(&sanitized, context.data_data_virtual_root, context.public_data_root)"));
        assertTrue("Raw host-package filtering must preserve app-private aliases long enough for path rewriting",
                source.contains("return !isVirtualAppDataLine(line, context) && shouldHideMapsLine(line);"));
    }

    @Test
    public void mapsShimPublishesProtectedPrivateLibrariesUnderDataDataAlias() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("Protected loaders commonly compare their private native path with the /data/data/<pkg> alias used by System.load",
                source.contains("\"/data/data/%s\", context->package_name")
                        || source.contains("\"/data/data/%s\", context.package_name"));
        assertFalse("The protected proc maps view should not publish /data/user/0 for app-private native libraries when the loader was invoked through /data/data",
                source.contains("\"/data/user/%s/%s\", context.user_id, context.package_name"));
    }

    @Test
    public void mapsShimNeverAddsSyntheticUnbackedAddressRanges() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");
        String mapsWriter = sliceBetween(source,
                "bool writeFakeMapsFile",
                "const ProcPathShim kProcPathShims[]");

        assertFalse("Maps shim must not synthesize fake address ranges: protected loaders may dereference every range they parse",
                source.contains("writeSyntheticProtectedLibraryMapping")
                        || source.contains("12c00000-12c01000"));
        assertTrue("Maps shim should only emit current real /proc/self/maps lines after path sanitization",
                source.contains("FILE *maps = openRealProcMapsFile()")
                        && source.contains("std::string sanitized = sanitizeMapsLine(line, context)")
                        && source.contains("writeExact(fd, sanitized.data(), sanitized.size())"));
        assertFalse("If real maps cannot be read, the maps shim should fail closed instead of inventing an unmapped placeholder",
                mapsWriter.contains("libprotected.so")
                        || mapsWriter.contains("fallback"));
    }

    @Test
    public void mapsShimRefreshesProcMapsBeforeEveryRedirectedRead() throws Exception {
        String runtimeSource = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");
        String fileHookSource = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("RuntimeHook should expose a generic proc-maps refresh entry point",
                runtimeSource.contains("refreshProtectedProcMapsShim"));
        assertTrue("Proc-maps refresh should publish a complete rebuilt fd93 snapshot instead of truncating the currently exposed fd",
                runtimeSource.contains("refreshProcMapsShimAtomically(gProcShimContext)")
                        && runtimeSource.contains("rename(")
                        && runtimeSource.contains("dup2(fd, kProcMapsFd)"));
        assertFalse("Refreshing fd93 in place can expose empty or partial maps to protected loaders",
                runtimeSource.contains("ftruncate(kProcMapsFd, 0)"));
        assertTrue("NativeFileHook should refresh maps fd93 before redirecting /proc/self/maps to /dev/fd/93",
                fileHookSource.contains("refreshProtectedProcMapsShim();")
                        && fileHookSource.indexOf("refreshProtectedProcMapsShim();")
                        < fileHookSource.indexOf("return kProcMapsFdPath;"));
    }

    @Test
    public void mapsShimRefreshesAfterNativeLoadSoLoadedLibraryAppearsInFdView() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        int nativeLoad = source.indexOf("HOOK_JNI(jstring, nativeLoad,");
        int loadResult = source.indexOf("jstring result = orig_nativeLoad", nativeLoad);
        int refresh = source.indexOf("refreshProtectedProcMapsShim();", loadResult);
        int installHooks = source.indexOf("installNativeFileHooks();", loadResult);
        int nativeLoad2 = source.indexOf("HOOK_JNI(jstring, nativeLoad2,");
        int load2Result = source.indexOf("jstring result = orig_nativeLoad2", nativeLoad2);
        int refresh2 = source.indexOf("refreshProtectedProcMapsShim();", load2Result);
        int installHooks2 = source.indexOf("installNativeFileHooks();", load2Result);

        assertTrue("RuntimeHook should import the proc-maps refresh entry point from the proc shim implementation",
                source.contains("extern \"C\" void refreshProtectedProcMapsShim();"));
        assertTrue("Android P- nativeLoad should refresh fd93 after the protected library is mapped and before post-load hooks run",
                nativeLoad >= 0 && loadResult > nativeLoad && refresh > loadResult && installHooks > refresh);
        assertTrue("Android Q+ nativeLoad should refresh fd93 after the protected library is mapped and before post-load hooks run",
                nativeLoad2 >= 0 && load2Result > nativeLoad2 && refresh2 > load2Result && installHooks2 > refresh2);
    }

    @Test
    public void mapsShimRefreshesConcurrentlyWhileProtectedNativeLoadRunsJniOnLoad() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        int prepare = source.indexOf("prepareProtectedProcShims(loadPath)");
        int start = source.indexOf("startProcMapsRefreshDuringNativeLoad();", prepare);
        int load = source.indexOf("orig_nativeLoad", start);
        int prepare2 = source.indexOf("prepareProtectedProcShims(loadPath)", load);
        int start2 = source.indexOf("startProcMapsRefreshDuringNativeLoad();", prepare2);
        int load2 = source.indexOf("orig_nativeLoad2", start2);

        assertTrue("Protected-library maps fd should refresh on a short background window while JNI_OnLoad executes",
                source.contains("procMapsRefreshThreadMain")
                        && source.contains("pthread_create")
                        && source.contains("pthread_detach")
                        && source.contains("refreshProtectedProcMapsShim();")
                        && source.contains("usleep("));
        assertTrue("Android P- nativeLoad should start the maps refresher after proc shims are prepared but before JNI_OnLoad can read fd93",
                prepare >= 0 && start > prepare && load > start);
        assertTrue("Android Q+ nativeLoad should start the maps refresher after proc shims are prepared but before JNI_OnLoad can read fd93",
                prepare2 >= 0 && start2 > prepare2 && load2 > start2);
    }

    @Test
    public void mapsShimKeepsOnlyRealProtectedLibraryMappingWhenAvailable() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("Maps shim should preserve real protected-library lines through the virtual-root filter",
                source.contains("!isVirtualAppDataLine(line, context)")
                        && source.contains("std::string sanitized = sanitizeMapsLine(line, context)"));
        assertFalse("Maps shim must not add a protected-library mapping unless that line exists in real /proc/self/maps",
                source.contains("wrote_protected_library_mapping")
                        || source.contains("if (!wrote_protected_library_mapping)")
                        || source.contains("writeSyntheticProtectedLibraryMapping"));
    }

    @Test
    public void mapsShimReadsRealProcMapsWithoutRecursingThroughFileHook() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("RuntimeHook should open real /proc/self/maps through libc, not through the interposed fopen",
                source.contains("#include <dlfcn.h>")
                        && source.contains("dlsym(RTLD_NEXT, \"fopen\")")
                        && source.contains("openRealProcMapsFile"));
    }

    @Test
    public void runtimeHookDoesNotPatchTargetSpecificJniOnLoadReturnBytes() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertFalse("RuntimeHook must not patch target-specific JNI_OnLoad return bytes",
                source.contains("JniOnLoadReturnPatch")
                        || source.contains("JNI_OnLoad return patch")
                        || source.contains("jniReturnPatched"));
        assertTrue("RuntimeHook should keep the generic proc-shim preparation path",
                source.contains("prepareProtectedProcShims(loadPath)")
                        && source.contains("patchProtectedProcStrings"));
    }

    @Test
    public void runtimeHookRedirectsPublicSystemLoadPathsBeforeNativeLoader() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("Runtime.nativeLoad hook should reuse IOCore rules for public /data/user load paths",
                source.contains("#include \"IO.h\"")
                        && source.contains("IO::redirectPath(nameC)"));
        assertTrue("Runtime.nativeLoad hook should prepare proc shims against the redirected real library path",
                source.contains("prepareProtectedProcShims(loadPath)"));
        assertTrue("Runtime.nativeLoad hook should pass the redirected path to the platform native loader",
                source.contains("NewStringUTF(loadPath)")
                        && source.contains("orig_nativeLoad(env, obj, loadName"));
        assertTrue("Runtime.nativeLoad hook should log path redirection evidence",
                source.contains("nativeLoad: %s redirected=%s"));
    }

    @Test
    public void runtimeHookLetsDtcLoaderObserveMissingJiaguDependency() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("Runtime.nativeLoad should inspect the platform loader result before returning it",
                source.contains("handleNativeLoadResult(env, result, nameC, loadPath)"));
        assertTrue("Runtime.nativeLoad should log platform loader errors for jiagu diagnostics",
                source.contains("nativeLoad result name=%s redirected=%s error=%s"));
        assertFalse("DtcLoader must see libjgdtc load failures so its fallback path matches direct app startup",
                source.contains("nativeLoad suppressed missing jiagu dependency"));
        assertFalse("RuntimeHook should not report a missing native dependency as successfully loaded",
                source.contains("DeleteLocalRef(result)"));
    }

}
