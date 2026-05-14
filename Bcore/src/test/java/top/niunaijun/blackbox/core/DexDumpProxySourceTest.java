package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DexDumpProxySourceTest {

    @Test
    public void dynamicDexLoadsAreHookedAndDumpedUnderHostFilesPackageDirectory() throws Exception {
        String proxy = readSource("Bcore/src/main/java/top/niunaijun/blackbox/fake/service/DexDumpProxy.java");
        String bActivityThread = readSource("Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");
        String nativeCore = readSource("Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");

        assertTrue("DexDumpProxy should hook BaseDexClassLoader constructors",
                proxy.contains("dalvik.system.BaseDexClassLoader"));
        assertTrue("DexDumpProxy should hook in-memory dex class loading when present",
                proxy.contains("dalvik.system.InMemoryDexClassLoader"));
        assertTrue("DexDumpProxy should dump ByteBuffer backed dex bytes",
                proxy.contains("NativeCore.dumpDexByteBuffers"));
        assertTrue("DexDumpProxy should dump file backed dynamic class loaders",
                proxy.contains("scheduleClassLoaderDump((ClassLoader)"));
        assertTrue("DexDumpProxy should dump string dex path arguments without DexFile native hooks",
                proxy.contains("NativeCore.dumpDexPath")
                        && !proxy.contains("openDexFileNative")
                        && !proxy.contains("openInMemoryDexFilesNative"));
        assertTrue("BActivityThread should install DexDumpProxy only after a virtual app is bound",
                bActivityThread.contains("new DexDumpProxy().injectHook()"));
        assertTrue("DexDumpProxy should be guarded against repeated installation in a process",
                proxy.contains("sInstalled"));
        assertTrue("NativeCore should write dynamic dex dumps into files/<pkg>",
                nativeCore.contains("dumpDexByteBuffers")
                        && nativeCore.contains("new File(BlackBoxCore.getContext().getFilesDir(), packageName)"));
        assertTrue("DexDumpProxy should keep constructor-triggered dumps off protected loader stacks",
                proxy.contains("scheduleStringPathArgs(callFrame.args")
                        && proxy.contains("scheduleDexFileDump((DexFile) callFrame.thisObject")
                        && proxy.contains("scheduleByteBufferArgs(callFrame.args"));
        assertFalse("DexDumpProxy must not synchronously dump class-loader or DexFile cookies inside constructor hooks",
                proxy.contains("dumpClassLoader((ClassLoader) callFrame.thisObject")
                        || proxy.contains("dumpDexFile((DexFile) callFrame.thisObject")
                        || proxy.contains("dumpStringPathArgs(callFrame.args"));
    }

    private static String readSource(String rootRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(rootRelativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootRelativePath + " not found from " + current);
    }
}
