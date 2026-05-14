package top.niunaijun.blackbox.utils;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;

public class AbiUtilsTest {

    @Test
    public void prefersLegacyArmeabiWhenItIsTheOnly32BitAbi() throws Exception {
        File apk = apkWithEntries("lib/armeabi/libgala_ashmem.so");

        assertEquals("armeabi", AbiUtils.getPreferredAbi(apk, false));
    }

    @Test
    public void distinguishesArmeabiV7aFromLegacyArmeabi() throws Exception {
        File apk = apkWithEntries("lib/armeabi-v7a/libblackbox.so");

        assertEquals("armeabi-v7a", AbiUtils.getPreferredAbi(apk, false));
    }

    @Test
    public void prefersArmeabiV7aOverLegacyArmeabiWhenBothArePresent() throws Exception {
        File apk = apkWithEntries(
                "lib/armeabi/libfallback.so",
                "lib/armeabi-v7a/libfast.so");

        assertEquals("armeabi-v7a", AbiUtils.getPreferredAbi(apk, false));
    }

    @Test
    public void prefersArm64WhenRunning64BitAndApkProvidesIt() throws Exception {
        File apk = apkWithEntries(
                "lib/armeabi/libfallback.so",
                "lib/arm64-v8a/libfast.so");

        assertEquals("arm64-v8a", AbiUtils.getPreferredAbi(apk, true));
    }

    private static File apkWithEntries(String... entries) throws Exception {
        File file = File.createTempFile("blackbox-abi", ".apk");
        file.deleteOnExit();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(new byte[]{0});
                zip.closeEntry();
            }
        }
        return file;
    }
}
