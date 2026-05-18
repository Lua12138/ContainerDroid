package top.niunaijun.blackbox.binder;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.binder.SourceAssertions.readSource;

public class BinderTransactInterceptorSourceTest {

    @Test
    public void binderProxyHookCanShortCircuitSelectedTransactions() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/binder/BlackBoxBinderMonitor.java",
                "Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BlackBoxBinderMonitor.java");

        assertTrue(source.contains("interface BinderTransactInterceptor"));
        assertTrue(source.contains("setTransactInterceptor"));
        assertTrue(source.contains("transactInterceptor"));
        assertTrue(source.contains("onBinderProxyTransact(callFrame.thisObject, callFrame.args)"));
        assertTrue(source.contains("callFrame.setResult(Boolean.TRUE)"));
        assertTrue(source.contains("interceptBinderTransact("));
    }
}
