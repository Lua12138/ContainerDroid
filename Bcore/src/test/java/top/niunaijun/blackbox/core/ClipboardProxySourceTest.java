package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class ClipboardProxySourceTest {

    @Test
    public void clipboardProxyDoesNotDereferenceLazyClipboardServiceInConstructor() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/IClipboardManagerProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IClipboardManagerProxy.java");

        assertFalse(source.contains("BRClipboardManager.get().getService().asBinder()"));
        assertTrue(source.contains("BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE)"));
        assertTrue(source.contains("BRIClipboardStub.get().asInterface"));
    }
}
