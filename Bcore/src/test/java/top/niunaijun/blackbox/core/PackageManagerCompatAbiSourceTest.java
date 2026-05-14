package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class PackageManagerCompatAbiSourceTest {

    @Test
    public void generatedApplicationInfoUsesApkPreferredAbiForPrimaryCpuAbi() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java");

        assertTrue("PackageManagerCompat should derive primaryCpuAbi from the APK native lib dirs",
                source.contains("AbiUtils.getPreferredAbi(new File(sourceDir))"));
        assertTrue("PackageManagerCompat should fall back to Build.CPU_ABI only when the APK has no preferred ABI",
                source.contains("if (primaryCpuAbi == null)") && source.contains("primaryCpuAbi = Build.CPU_ABI"));
        assertTrue("ApplicationInfo.primaryCpuAbi should be set to the selected APK ABI",
                source.contains("_set_primaryCpuAbi(primaryCpuAbi)"));
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
