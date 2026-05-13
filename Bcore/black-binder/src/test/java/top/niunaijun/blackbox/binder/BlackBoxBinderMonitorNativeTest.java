package top.niunaijun.blackbox.binder;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BlackBoxBinderMonitorNativeTest {

    @Test
    public void recordNativeBinderTransactEmitsWhenNativeRecordingEnabled() {
        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_native\":true,"
                        + "\"record_ioctl\":false,"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                identity());

        BlackBoxBinderMonitor.recordNativeBinderTransact(
                "android.app.IActivityManager",
                3,
                0,
                128,
                true,
                "Native.BpBinder.transact");

        List<String> events = BlackBoxBinderMonitor.snapshotEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"descriptor\":\"android.app.IActivityManager\""));
        assertTrue(events.get(0).contains("\"method\":\"startActivity\""));
        assertTrue(events.get(0).contains("\"source\":\"Native.BpBinder.transact\""));
    }

    @Test
    public void recordIoctlBinderTransactionEmitsOnlyWhenIoctlRecordingEnabled() {
        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_native\":false,"
                        + "\"record_ioctl\":false,"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                identity());

        BlackBoxBinderMonitor.recordIoctlBinderTransaction(
                "android.content.pm.IPackageManager",
                3,
                1,
                256,
                true);
        assertEquals(0, BlackBoxBinderMonitor.snapshotEvents().size());

        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_native\":false,"
                        + "\"record_ioctl\":true,"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                identity());

        BlackBoxBinderMonitor.recordIoctlBinderTransaction(
                "android.content.pm.IPackageManager",
                3,
                1,
                256,
                true);

        List<String> events = BlackBoxBinderMonitor.snapshotEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"descriptor\":\"android.content.pm.IPackageManager\""));
        assertTrue(events.get(0).contains("\"method\":\"getPackageInfo\""));
        assertTrue(events.get(0).contains("\"oneway\":true"));
        assertTrue(events.get(0).contains("\"source\":\"Native.ioctl.BINDER_WRITE_READ\""));
    }

    @Test
    public void recordIoctlBinderTransactionIncludesHandleAndDriverCommand() {
        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_native\":false,"
                        + "\"record_ioctl\":true,"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                identity());

        BlackBoxBinderMonitor.recordIoctlBinderTransaction(
                "android.content.pm.IPackageManager",
                3,
                1,
                256,
                true,
                42,
                "BC_TRANSACTION");

        List<String> events = BlackBoxBinderMonitor.snapshotEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"handle\":42"));
        assertTrue(events.get(0).contains("\"driver_command\":\"BC_TRANSACTION\""));
    }

    @Test
    public void nativeBinderTransactDeduplicatesRecentJavaBinderEvent() {
        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_native\":true,"
                        + "\"record_ioctl\":false,"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                identity());

        BlackBoxBinderMonitor.recordJavaBinderTransactForTesting(
                "android.app.IActivityManager",
                3,
                0,
                128,
                true);
        BlackBoxBinderMonitor.recordNativeBinderTransact(
                "android.app.IActivityManager",
                3,
                0,
                128,
                true,
                "Native.BpBinder.transact");

        List<String> events = BlackBoxBinderMonitor.snapshotEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"source\":\"Pine.BinderProxy.transact\""));
    }

    @Test
    public void stackCaptureRequiresExplicitWatchFilter() {
        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_stack\":true,"
                        + "\"record_native\":true,"
                        + "\"record_ioctl\":false,"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                identity());

        BlackBoxBinderMonitor.recordNativeBinderTransact(
                "android.app.IActivityManager",
                3,
                0,
                128,
                true,
                "Native.BpBinder.transact");

        List<String> events = BlackBoxBinderMonitor.snapshotEvents();
        assertEquals(1, events.size());
        assertTrue(!events.get(0).contains("\"call_stack\""));
    }

    @Test
    public void stackCaptureRunsWhenDescriptorIsWatched() {
        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_stack\":true,"
                        + "\"record_native\":true,"
                        + "\"record_ioctl\":false,"
                        + "\"watch_descriptors\":[\"android.app.IActivityManager\"],"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                identity());

        BlackBoxBinderMonitor.recordNativeBinderTransact(
                "android.app.IActivityManager",
                3,
                0,
                128,
                true,
                "Native.BpBinder.transact");

        List<String> events = BlackBoxBinderMonitor.snapshotEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0).contains("\"call_stack\""));
    }

    @Test
    public void crashContextKeepsLastHundredBinderEvents() {
        BlackBoxBinderMonitor.initForTesting(
                BinderMonitorConfig.fromJson("{"
                        + "\"enabled\":true,"
                        + "\"record_native\":true,"
                        + "\"record_ioctl\":false,"
                        + "\"max_ring_events\":256,"
                        + "\"logcat\":false,"
                        + "\"output\":\"none\""
                        + "}"),
                identity());

        for (int i = 0; i < 105; i++) {
            BlackBoxBinderMonitor.recordNativeBinderTransact(
                    "android.app.IActivityManager",
                    i,
                    0,
                    128,
                    true,
                    "Native.BpBinder.transact");
        }

        BlackBoxBinderMonitor.writeCrashContext(new RuntimeException("boom"));

        List<String> events = BlackBoxBinderMonitor.snapshotEvents();
        String crash = events.get(events.size() - 1);
        assertTrue(crash.contains("\"type\":\"crash_context\""));
        assertTrue(crash.contains("\"code\":5,\"flags\""));
        assertTrue(crash.contains("\"code\":104,\"flags\""));
        assertTrue(!crash.contains("\"code\":0,\"flags\""));
        assertTrue(!crash.contains("\"code\":4,\"flags\""));
    }

    private static VirtualIdentity identity() {
        return new VirtualIdentity("blackbox:p0", "com.example.app", "com.example.app", 1001, 0, 12);
    }
}
