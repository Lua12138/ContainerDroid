package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

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
}
