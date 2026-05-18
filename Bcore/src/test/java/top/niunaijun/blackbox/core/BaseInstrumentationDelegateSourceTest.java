package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

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
}
