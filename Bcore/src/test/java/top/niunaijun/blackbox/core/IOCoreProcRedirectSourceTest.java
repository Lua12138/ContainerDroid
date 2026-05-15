package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class IOCoreProcRedirectSourceTest {

    @Test
    public void procRedirectsKernelVersionToReadableVirtualFile() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/core/IOCore.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/IOCore.java");

        int proc = source.indexOf("private void proc(Map<String, String> rule)");
        int versionFile = source.indexOf("ensureProcVersionFile", proc);
        int redirect = source.indexOf("rule.put(\"/proc/version\"", proc);

        assertTrue("IOCore should configure /proc redirects", proc >= 0);
        assertTrue("IOCore should create a readable virtual /proc/version file",
                versionFile > proc && versionFile < redirect);
        assertTrue("IOCore should redirect /proc/version to the virtual file",
                redirect > versionFile);
        assertTrue("The fake /proc/version should look like a Linux kernel banner",
                source.contains("\"Linux version \""));
    }

    @Test
    public void virtualProcCmdlineKeepsAndroidStyleNulPaddedArgBlock() throws Exception {
        String processManager = readSource(
                "src/main/java/top/niunaijun/blackbox/core/system/BProcessManagerService.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/system/BProcessManagerService.java");
        String runtimeHook = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("Virtual /proc/<pid>/cmdline should be written through a shared Android-style builder",
                processManager.contains("PROC_CMDLINE_MIN_BYTES = 76")
                        && processManager.contains("buildProcCmdline(record.processName)")
                        && processManager.contains("StandardCharsets.UTF_8")
                        && processManager.contains("Math.max(PROC_CMDLINE_MIN_BYTES, nameBytes.length + 1)"));
        assertTrue("The protected proc-shim cmdline writer should use the same NUL-padded minimum block shape",
                runtimeHook.contains("kProcCmdlineMinBytes = 76")
                        && runtimeHook.contains("std::string cmdline(length, '\\0')")
                        && runtimeHook.contains("writeExact(fd, cmdline.data(), cmdline.size())"));
    }

    @Test
    public void procMapsRedirectsToSanitizedAppVisibleSnapshot() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/core/IOCore.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/IOCore.java");

        assertTrue("redirectPath should special-case /proc/self/maps before normal app-data redirects",
                source.contains("redirectProcMapsPath(path)")
                        && source.indexOf("redirectProcMapsPath(path)") < source.indexOf("if (path.contains(\"/blackbox/\"))"));
        assertTrue("IOCore should recognize both /proc/self/maps and the current-pid canonical /proc/<pid>/maps path",
                source.contains("\"/proc/self/maps\"")
                        && source.contains("\"/proc/\" + Process.myPid() + \"/maps\""));
        assertTrue("virtual maps should be refreshed into the per-process virtual proc directory",
                source.contains("ensureProcMapsFile")
                        && source.contains("BEnvironment.getProcDir(appPid)")
                        && source.contains("new File(procDir, \"maps\")"));
        assertTrue("refreshing the virtual maps file must not recursively redirect its own /proc/self/maps read",
                source.contains("sRefreshingProcMaps")
                        && source.contains("Boolean.TRUE.equals(sRefreshingProcMaps.get())")
                        && source.contains("sRefreshingProcMaps.remove()"));
        assertTrue("virtual maps refresh must be throttled enough that repeated File/stat/access probes reuse the snapshot instead of rewriting it per call",
                source.contains("PROC_MAPS_REFRESH_INTERVAL_MS = 60000L"));
        assertTrue("virtual maps should be marked read-only after refresh so File.canWrite resembles procfs",
                source.contains("setWritable(true, true)")
                        && source.contains("setWritable(false, false)"));
        assertTrue("sanitizer must hide BlackBox/Pine implementation mappings and writable-executable mappings",
                source.contains("shouldHideProcMapsLine")
                        && source.contains("isWritableExecutableProcMapsLine")
                        && source.contains("libblackbox")
                        && source.contains("libpine")
                        && source.contains("[anon:pine codes]"));
        assertTrue("sanitizer should reverse BlackBox data roots back to app-visible /data/user paths",
                source.contains("replaceBlackBoxDataUserRoots")
                        && source.contains("\"/blackbox/data/user/\"")
                        && source.contains("\"/data/user/\""));
    }

    private static String readSource(String moduleRelativePath, String rootRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path moduleCandidate = dir.resolve(moduleRelativePath);
            if (Files.isRegularFile(moduleCandidate)) {
                return new String(Files.readAllBytes(moduleCandidate), StandardCharsets.UTF_8);
            }
            Path rootCandidate = dir.resolve(rootRelativePath);
            if (Files.isRegularFile(rootCandidate)) {
                return new String(Files.readAllBytes(rootCandidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootRelativePath + " not found from " + current);
    }
}
