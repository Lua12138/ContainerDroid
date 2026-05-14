package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class NoTargetHardcodedInterceptionSourceTest {
    private static final List<String> PRODUCTION_ROOTS = Arrays.asList(
            "Bcore/src/main",
            "Bcore/black-binder/src/main",
            "Bcore/pine-core/src/main",
            "app/src/main",
            "android-mirror/src/main");

    private static final List<String> FORBIDDEN_TARGET_MARKERS = Arrays.asList(
            "com.bestv.tv.video.iqy.tjdx",
            "com.bestv.iptv.tv",
            "com.bestv.ott",
            "BestV",
            "Bestv",
            "bestv",
            "TelnetCommand",
            "WONT",
            "AppCompatSpinner$DropDownAdapter",
            "unregisterDataSetObserver",
            "Jiagu",
            "jiagu");

    @Test
    public void productionCodeDoesNotContainTargetSpecificInterceptionMarkers() throws Exception {
        Path root = repoRoot();
        List<String> violations = new ArrayList<>();

        for (String relativeRoot : PRODUCTION_ROOTS) {
            Path productionRoot = root.resolve(relativeRoot);
            if (!Files.isDirectory(productionRoot)) {
                continue;
            }
            Files.walk(productionRoot)
                    .filter(Files::isRegularFile)
                    .filter(NoTargetHardcodedInterceptionSourceTest::isSourceFile)
                    .forEach(path -> collectViolations(root, path, violations));
        }

        assertTrue("Target-specific interception markers must not appear in production source:\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    private static void collectViolations(Path root, Path path, List<String> violations) {
        String source;
        try {
            source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("Unable to read " + path, e);
        }
        for (String marker : FORBIDDEN_TARGET_MARKERS) {
            if (source.contains(marker)) {
                violations.add(root.relativize(path) + " contains " + marker);
            }
        }
    }

    private static boolean isSourceFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java")
                || name.endsWith(".kt")
                || name.endsWith(".cpp")
                || name.endsWith(".cc")
                || name.endsWith(".c")
                || name.endsWith(".h")
                || name.endsWith(".hpp")
                || name.endsWith(".gradle");
    }

    private static Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".git"))) {
                return dir;
            }
        }
        throw new AssertionError("repo root not found from " + current);
    }
}
