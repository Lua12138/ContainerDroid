package top.niunaijun.blackbox.binder;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BinderMonitorConfigTest {

    @Test
    public void fromJsonParsesFiltersAndFlags() {
        BinderMonitorConfig config = BinderMonitorConfig.fromJson("{"
                + "\"enabled\":true,"
                + "\"packages\":[\"com.example.app\"],"
                + "\"record_stack\":true,"
                + "\"record_proxy\":false,"
                + "\"record_native\":true,"
                + "\"record_ioctl\":false,"
                + "\"watch_descriptors\":[\"android.app.IActivityManager\"],"
                + "\"processes\":[\"com.example.app:remote\"],"
                + "\"watch_methods\":[\"startActivity\"],"
                + "\"watch_codes\":[3],"
                + "\"watch_flags\":[16],"
                + "\"watch_threads\":[7],"
                + "\"max_ring_events\":64,"
                + "\"output\":\"jsonl\""
                + "}");

        assertTrue(config.isEnabled());
        assertTrue(config.shouldRecordPackage("com.example.app"));
        assertFalse(config.shouldRecordPackage("com.other.app"));
        assertTrue(config.shouldRecordDescriptor("android.app.IActivityManager"));
        assertFalse(config.shouldRecordDescriptor("android.accounts.IAccountManager"));
        assertTrue(config.shouldRecordProcess("com.example.app:remote"));
        assertFalse(config.shouldRecordProcess("com.example.app"));
        assertTrue(config.shouldRecordMethod("startActivity"));
        assertFalse(config.shouldRecordMethod("bindService"));
        assertTrue(config.shouldRecordCode(3));
        assertFalse(config.shouldRecordCode(4));
        assertTrue(config.shouldRecordFlags(16));
        assertFalse(config.shouldRecordFlags(1));
        assertTrue(config.shouldRecordThread(7));
        assertFalse(config.shouldRecordThread(8));
        assertTrue(config.isRecordStack());
        assertFalse(config.isRecordProxy());
        assertTrue(config.isRecordNative());
        assertFalse(config.isRecordIoctl());
        assertEquals(64, config.getMaxRingEvents());
        assertEquals("jsonl", config.getOutput());
    }

    @Test
    public void defaultConfigRecordsJavaAndProxyEventsOnly() {
        BinderMonitorConfig config = BinderMonitorConfig.defaultConfig();

        assertEquals(BuildConfig.DEBUG, config.isEnabled());
        assertTrue(config.shouldRecordPackage("any.package"));
        assertTrue(config.shouldRecordDescriptor("any.descriptor"));
        assertFalse(config.isRecordStack());
        assertTrue(config.isRecordProxy());
        assertFalse(config.isRecordNative());
        assertFalse(config.isRecordIoctl());
        assertEquals(2048, config.getMaxRingEvents());
        assertEquals(BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED, config.isLogcat());
        assertFalse(config.withLogcat(false).isLogcat());
        assertFalse(config.withEnabled(false).isEnabled());
    }

    @Test
    public void fromJsonFileOverridesDefaultsWhenFileExists() throws Exception {
        File file = File.createTempFile("binder-monitor", ".json");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("{\"enabled\":true,\"record_native\":true,\"record_ioctl\":true}");
        }

        BinderMonitorConfig config = BinderMonitorConfig.fromJsonFile(file, BinderMonitorConfig.defaultConfig());

        assertTrue(config.isEnabled());
        assertTrue(config.isRecordNative());
        assertTrue(config.isRecordIoctl());
    }
}
