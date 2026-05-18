package top.niunaijun.blackbox.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

final class SourceAssertions {
    private SourceAssertions() {
    }

    static String readSource(String... candidates) throws Exception {
        String source = readOptionalSource(candidates);
        if (source != null) {
            return source;
        }
        throw new AssertionError(candidates[0] + " not found from " + Paths.get("").toAbsolutePath());
    }

    static String readOptionalSource(String... candidates) throws Exception {
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            for (String candidate : candidates) {
                Path path = dir.resolve(candidate);
                if (Files.isRegularFile(path)) {
                    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    static String sliceBetween(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(startNeedle + " should exist", start >= 0);
        assertTrue(endNeedle + " should exist after " + startNeedle, end > start);
        return source.substring(start, end);
    }

    static String sliceBetweenOrTail(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        if (start < 0) {
            return "";
        }
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        if (end < 0) {
            return source.substring(start);
        }
        return source.substring(start, end);
    }
}
