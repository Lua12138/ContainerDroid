package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class NativeCmakeHardeningSourceTest {
    @Test
    public void blackboxNativeLibraryUsesHiddenVisibilityAndStrippedLinkOutput() throws Exception {
        String cmake = read("src/main/cpp/CMakeLists.txt");

        assertTrue(cmake.contains("target_compile_options(blackbox PRIVATE"));
        assertTrue(cmake.contains("-fvisibility=hidden"));
        assertTrue(cmake.contains("-fvisibility-inlines-hidden"));
        assertTrue(cmake.contains("check_cxx_compiler_flag(\"-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.\""));
        assertTrue(cmake.contains("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=."));
        assertTrue(cmake.contains("-fdebug-prefix-map=${CMAKE_SOURCE_DIR}=."));
        assertTrue(cmake.contains("-Wl,--strip-all"));
        assertTrue(cmake.contains("-Wl,--exclude-libs,ALL"));
        assertTrue(cmake.contains("-Wl,-soname,libblackbox.so"));
        assertTrue(read("src/main/cpp/Utils/XorString.h").contains("#define BB_CORE_STR"));
    }

    @Test
    public void pineNativeLibraryUsesHiddenVisibilityAndStrippedLinkOutput() throws Exception {
        String cmake = read("pine-core/src/main/cpp/CMakeLists.txt");

        assertTrue(cmake.contains("target_compile_options(pine PRIVATE"));
        assertTrue(cmake.contains("-fvisibility=hidden"));
        assertTrue(cmake.contains("-fvisibility-inlines-hidden"));
        assertTrue(cmake.contains("check_cxx_compiler_flag(\"-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.\""));
        assertTrue(cmake.contains("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=."));
        assertTrue(cmake.contains("-fdebug-prefix-map=${CMAKE_SOURCE_DIR}=."));
        assertTrue(cmake.contains("-Wl,--strip-all"));
        assertTrue(cmake.contains("-Wl,--exclude-libs,ALL"));
        assertTrue(cmake.contains("-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/pine.exports.map"));
        assertTrue(cmake.contains("-Wl,-soname,libpine.so"));
        assertTrue(read("pine-core/src/main/cpp/utils/xor_string.h").contains("#define PINE_STR"));
        assertTrue(read("pine-core/src/main/cpp/pine.exports.map").contains("bbp_*;"));
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
