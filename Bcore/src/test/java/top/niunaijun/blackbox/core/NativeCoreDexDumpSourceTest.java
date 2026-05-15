package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeCoreDexDumpSourceTest {

    @Test
    public void dumpDexWritesClassesDexEntriesUnderHostFilesPackageDirectory() throws Exception {
        String source = readSource("Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String nativeSource = readSource("Bcore/src/main/cpp/BoxCore.cpp");

        assertTrue("dumpDex should write into host files/<pkg>",
                source.contains("new File(BlackBoxCore.getContext().getFilesDir(), packageName)"));
        assertTrue("dumpDex should read dex entries from APK or dex containers",
                source.contains("ZipFile")
                        && source.contains("classes.dex")
                        && source.contains("FileUtils.writeToFile"));
        assertFalse("dumpDex must not leave the actual dump call commented out",
                source.contains("//            dumpDex(cookie"));
        assertTrue("in-memory dex fallback names should use content SHA-1 to avoid repeated dumps",
                source.contains("MessageDigest.getInstance(\"SHA-1\")")
                        && source.contains("String digest = sha1(bytes)"));
        assertTrue("native cookie dex fallback names should use full file content SHA-1, not address-based or header-signature names",
                nativeSource.contains("sha1Hex(begin, size)")
                        && nativeSource.contains("\"%s/cookie_%s.dex\"")
                        && nativeSource.contains("gDumpedDexCookieSignatures"));
        assertFalse("DEX header signature alone is not the requested full-content SHA-1 filename",
                nativeSource.contains("dexSignatureSha1Hex"));
        assertFalse("native cookie dex dumps must not be keyed by volatile memory addresses",
                nativeSource.contains("cookie_%d_%d_%08"));
    }

    @Test
    public void inMemoryDexDumpIsBoundedToAvoidMainThreadAnrAndStorageExhaustion() throws Exception {
        String source = readSource("Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String memoryDump = sliceBetween(source,
                "public static void dumpDexByteBuffers",
                "private static Set<String> collectDexSourcePaths");

        assertTrue("ByteBuffer dump should cap per-call buffer count",
                source.contains("MAX_MEMORY_DEX_BUFFERS_PER_CALL")
                        && memoryDump.contains("dumpedBuffers >= MAX_MEMORY_DEX_BUFFERS_PER_CALL"));
        assertTrue("ByteBuffer dump should cap each individual buffer before allocating/copying it",
                source.contains("MAX_MEMORY_DEX_BUFFER_BYTES")
                        && memoryDump.contains("size > MAX_MEMORY_DEX_BUFFER_BYTES"));
        assertTrue("ByteBuffer dump should cap total bytes written by one call",
                source.contains("MAX_MEMORY_DEX_BYTES_PER_CALL")
                        && memoryDump.contains("dumpedBytes + size > MAX_MEMORY_DEX_BYTES_PER_CALL"));
        assertTrue("ByteBuffer dump should only account bytes after a successful write",
                memoryDump.indexOf("FileUtils.writeToFile") < memoryDump.indexOf("dumpedBytes += size"));
    }

    @Test
    public void bindApplicationInvokesDumpDexAfterApplicationClassLoaderIsAvailable() throws Exception {
        String source = readSource("Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue("BActivityThread should schedule dex dump after Application is created without blocking launch",
                source.contains("DexDumpProxy.scheduleClassLoaderDump(application.getClassLoader(), packageName"));
        assertFalse("BActivityThread should not synchronously dump dex on Application launch paths",
                source.contains("NativeCore.dumpDex(application.getClassLoader(), packageName)"));
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

    private static String sliceBetween(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(startNeedle + " should exist", start >= 0);
        assertTrue(endNeedle + " should exist after " + startNeedle, end > start);
        return source.substring(start, end);
    }
}
