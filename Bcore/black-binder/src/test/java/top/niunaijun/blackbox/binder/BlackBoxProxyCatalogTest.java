package top.niunaijun.blackbox.binder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BlackBoxProxyCatalogTest {

    @Test
    public void resolvesPlanProxyClassesToServiceAndDescriptor() {
        assertEquals("activity", BlackBoxProxyCatalog.getServiceName("IActivityManagerProxy"));
        assertEquals("android.app.IActivityManager",
                BlackBoxProxyCatalog.getInterfaceDescriptor("IActivityManagerProxy"));

        assertEquals("package", BlackBoxProxyCatalog.getServiceName("IPackageManagerProxy"));
        assertEquals("android.content.pm.IPackageManager",
                BlackBoxProxyCatalog.getInterfaceDescriptor("IPackageManagerProxy"));

        assertEquals("content", BlackBoxProxyCatalog.getServiceName("ContentServiceStub"));
        assertEquals("android.content.IContentService",
                BlackBoxProxyCatalog.getInterfaceDescriptor("ContentServiceStub"));

        assertEquals("content_provider", BlackBoxProxyCatalog.getServiceName("ContentProviderStub"));
        assertEquals("android.content.IContentProvider",
                BlackBoxProxyCatalog.getInterfaceDescriptor("ContentProviderStub"));

        assertEquals("settings_provider", BlackBoxProxyCatalog.getServiceName("SystemProviderStub"));
        assertEquals("android.content.IContentProvider",
                BlackBoxProxyCatalog.getInterfaceDescriptor("SystemProviderStub"));

        assertEquals("clipboard", BlackBoxProxyCatalog.getServiceName("IClipboardManagerProxy"));
        assertEquals("android.content.IClipboard",
                BlackBoxProxyCatalog.getInterfaceDescriptor("IClipboardManagerProxy"));
    }

    @Test
    public void resolvesDescriptorByServiceName() {
        assertEquals("android.app.INotificationManager",
                BlackBoxProxyCatalog.getInterfaceDescriptorForService("notification"));
        assertEquals("android.location.ILocationManager",
                BlackBoxProxyCatalog.getInterfaceDescriptorForService("location"));
    }
}
