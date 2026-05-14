package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class BaseInstrumentationDelegateSourceTest {

    @Test
    public void onExceptionLogsThrowableBeforeDelegatingToBaseInstrumentation() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/delegate/BaseInstrumentationDelegate.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/BaseInstrumentationDelegate.java");

        int method = source.indexOf("public boolean onException(Object obj, Throwable e)");
        int log = source.indexOf("Slog.e(TAG, \"Instrumentation onException\"", method);
        int delegate = source.indexOf("return mBaseInstrumentation.onException(obj, e)", method);

        assertTrue("BaseInstrumentationDelegate should override onException", method >= 0);
        assertTrue("onException should log the original Throwable before framework swallowing",
                log > method && log < delegate);
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
