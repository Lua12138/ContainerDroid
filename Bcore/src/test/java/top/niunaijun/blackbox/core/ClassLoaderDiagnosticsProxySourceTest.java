package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClassLoaderDiagnosticsProxySourceTest {

    @Test
    public void classLoaderDiagnosticsLogsClassNotFoundWithoutInterception() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/ClassLoaderDiagnosticsProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ClassLoaderDiagnosticsProxy.java");

        assertTrue("ClassLoader diagnostics should hook the public loadClass(String) path used by shell stubs",
                source.contains("hookLoadClass(String.class)")
                        && source.contains("ClassLoader.class.getDeclaredMethod(\"loadClass\", parameterTypes)"));
        assertTrue("ClassLoader diagnostics should also hook loadClass(String, boolean) for direct resolver calls",
                source.contains("hookLoadClass(String.class, boolean.class)")
                        && source.contains("ClassLoader.class.getDeclaredMethod(\"loadClass\", parameterTypes)"));
        assertTrue("Diagnostics should only log ClassNotFoundException outcomes",
                source.contains("callFrame.getThrowable()")
                        && source.contains("instanceof ClassNotFoundException"));
        assertTrue("Diagnostics should capture the class loader identity and stack for loader mismatch analysis",
                source.contains("classLoaderSummary(")
                        && source.contains("stackTraceSummary()"));
        assertTrue("Diagnostics should be rate limited to avoid broad loadClass log floods",
                source.contains("AtomicInteger")
                        && source.contains("MAX_FAILURE_RECORDS"));
        assertTrue("ClassLoader diagnostics should be explicit opt-in because Pine loadClass hooks are loader-visible",
                source.contains("debug.blackbox.classloader_diag")
                        && source.contains("isDiagnosticsEnabled()")
                        && source.contains("if (!isDiagnosticsEnabled())"));
        assertTrue("Diagnostics should emit through BlackBoxBinderMonitor for correlation with binder/crash context",
                source.contains("BlackBoxBinderMonitor.recordProxyCall")
                        && source.contains("\"classloader\"")
                        && source.contains("\"java.lang.ClassLoader\"")
                        && source.contains("\"loadClass\""));
        assertFalse("Diagnostics must not convert ClassNotFoundException into a fake class result",
                source.contains("callFrame.setResult"));
        assertFalse("Diagnostics must not hide or replace loadClass exceptions",
                source.contains("callFrame.setThrowable"));
        assertFalse("Diagnostics must not hardcode the current target package",
                source.contains("com.bestv.tv.video.iqy.tjdx")
                        || source.contains("com.bestv"));
    }

    @Test
    public void hookManagerInstallsClassLoaderDiagnosticsInSandboxProcesses() throws Exception {
        String hookManager = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertTrue("HookManager should import the class loader diagnostics injector",
                hookManager.contains("import top.niunaijun.blackbox.fake.service.ClassLoaderDiagnosticsProxy;"));
        assertTrue("HookManager should install the diagnostics injector during sandbox hook initialization",
                hookManager.contains("addInjector(new ClassLoaderDiagnosticsProxy())"));
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
