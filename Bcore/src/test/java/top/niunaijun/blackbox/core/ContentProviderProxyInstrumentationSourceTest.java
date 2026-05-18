package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class ContentProviderProxyInstrumentationSourceTest {

    @Test
    public void contentProviderStubsRecordProxyEvents() throws Exception {
        String contentProviderStub = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/context/providers/ContentProviderStub.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/context/providers/ContentProviderStub.java");
        String systemProviderStub = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.java");

        assertTrue(contentProviderStub.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertTrue(contentProviderStub.contains("\"content_provider\""));
        assertTrue(contentProviderStub.contains("\"android.content.IContentProvider\""));
        assertTrue(contentProviderStub.contains("argsRewritten"));

        assertTrue(systemProviderStub.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertTrue(systemProviderStub.contains("\"settings_provider\""));
        assertTrue(systemProviderStub.contains("\"android.content.IContentProvider\""));
        assertTrue(systemProviderStub.contains("argsRewritten"));
    }
}
