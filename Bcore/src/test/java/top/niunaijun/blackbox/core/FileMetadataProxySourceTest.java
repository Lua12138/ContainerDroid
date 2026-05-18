package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class FileMetadataProxySourceTest {

    @Test
    public void fileMetadataProxyHooksJavaFileSystemMetadataLayer() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/FileMetadataProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/FileMetadataProxy.java");
        assertTrue("FileMetadataProxy should exist for Java File metadata sanitization",
                !source.isEmpty());
        String hookManager = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertTrue("FileMetadataProxy should be registered with the generic injector set",
                hookManager.contains("new FileMetadataProxy()"));
        assertTrue("Proxy should target java.io.File's actual FileSystem instance rather than JNI native stubs",
                source.contains("class FileMetadataProxy implements IInjectHook")
                        && source.contains("File.class.getDeclaredField(\"fs\")")
                        && source.contains("getDeclaredMethod(\"checkAccess\", File.class, int.class)")
                        && source.contains("getDeclaredMethod(\"getLength\", File.class)"));
        assertTrue("File metadata hooks should use Pine Java method hooks because Android 11 checkAccess/getLength are not native",
                source.contains("Pine.hook(checkAccess")
                        && source.contains("Pine.hook(getLength"));
        assertTrue("File.canWrite(/proc/*/cmdline) should expose procfs write denial, not writable shim-file metadata",
                source.contains("ACCESS_WRITE = 0x02")
                        && source.contains("isProcCmdlineFile")
                        && source.contains("callFrame.setResult(false)"));
        assertTrue("File.length(/proc/*/cmdline) should expose procfs zero-length metadata",
                source.contains("isProcCmdlinePath")
                        && source.contains("/blackbox/proc/"));
        assertTrue("File.length for proc metadata should return zero",
                source.contains("callFrame.setResult(0L)"));
    }

    @Test
    public void unixFileSystemHookDoesNotTryToJniHookNonNativeMetadataMethods() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/UnixFileSystemHook.cpp",
                "Bcore/src/main/cpp/Hook/UnixFileSystemHook.cpp");

        assertFalse("Android 11 UnixFileSystem.checkAccess(File,int) is Java, so JniHook must not attempt it",
                source.contains("Hook(env, clazz, \"checkAccess\", \"(Ljava/io/File;I)Z\""));
        assertFalse("Android 11 UnixFileSystem.getLength(File) is Java, so JniHook must not attempt it",
                source.contains("Hook(env, clazz, \"getLength\", \"(Ljava/io/File;)J\""));
        assertFalse("Vendor-only zero-suffixed guesses should not be installed when they only create skip-hook noise",
                source.contains("Hook(env, clazz, \"checkAccess0\", \"(Ljava/io/File;I)Z\"")
                        || source.contains("Hook(env, clazz, \"getLength0\", \"(Ljava/io/File;)J\""));
    }
}
