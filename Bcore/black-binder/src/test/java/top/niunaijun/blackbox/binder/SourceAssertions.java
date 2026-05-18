package top.niunaijun.blackbox.binder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class SourceAssertions {
    private SourceAssertions() {
    }

    static String readSource(String... candidates) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            for (String candidate : candidates) {
                Path path = dir.resolve(candidate);
                if (Files.isRegularFile(path)) {
                    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(candidates[0] + " not found from " + current);
    }
}
