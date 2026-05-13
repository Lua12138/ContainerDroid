package top.niunaijun.blackbox.binder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProxyEventTest {

    @Test
    public void proxyEventSerializesProxyDecisionBooleans() {
        VirtualIdentity identity = new VirtualIdentity(
                "blackbox:p0",
                "com.example.app",
                "com.example.app",
                1001,
                0,
                12);

        ProxyEvent event = ProxyEvent.create(
                123456789L,
                identity,
                "activity",
                "android.app.IActivityManager",
                "startActivity",
                "IActivityManagerProxy",
                "2 args",
                "android.content.Intent",
                "handled",
                false,
                true,
                false);

        String json = event.toJson();

        assertTrue(json.contains("\"forwarded_host\":false"));
        assertTrue(json.contains("\"rewritten\":true"));
        assertTrue(json.contains("\"blocked\":false"));
    }

    @Test
    public void monitorSkipsUncatalogedNonBinderProxyCalls() {
        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_proxy\":true,"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                new VirtualIdentity("blackbox:p0", "com.example.app", "com.example.app", 1001, 0, 12));

        BlackBoxBinderMonitor.recordProxyCall(
                null,
                null,
                "write",
                "OsStub",
                "4 args",
                "java.lang.Integer",
                "forwarded");

        assertEquals(0, BlackBoxBinderMonitor.snapshotEvents().size());
    }
}
