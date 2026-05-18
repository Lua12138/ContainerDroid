package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;
import static top.niunaijun.blackbox.core.SourceAssertions.sliceBetween;

public class NativePropertySourceTest {

    @Test
    public void nativePropertyHelpersPreserveHistoricalTruthSets() throws Exception {
        String source = readSource("Bcore/src/main/cpp/Utils/NativeProperty.h");

        String defaultTruthy = sliceBetween(source,
                "inline bool isTruthy(const char *value)",
                "inline bool isTruthyJniDiagnostic");
        assertTrue(defaultTruthy.contains("\"YES\""));
        assertTrue(defaultTruthy.contains("\"on\""));
        assertTrue(defaultTruthy.contains("\"ON\""));

        String jniTruthy = sliceBetween(source,
                "inline bool isTruthyJniDiagnostic(const char *value)",
                "inline bool isTruthySeccompWatchdog");
        assertTrue(jniTruthy.contains("\"on\""));
        assertFalse(jniTruthy.contains("\"YES\""));
        assertFalse(jniTruthy.contains("\"ON\""));

        String watchdogTruthy = sliceBetween(source,
                "inline bool isTruthySeccompWatchdog(const char *value)",
                "inline bool isFalsy");
        assertTrue(watchdogTruthy.contains("\"YES\""));
        assertFalse(watchdogTruthy.contains("\"on\""));
        assertFalse(watchdogTruthy.contains("\"ON\""));
    }
}
