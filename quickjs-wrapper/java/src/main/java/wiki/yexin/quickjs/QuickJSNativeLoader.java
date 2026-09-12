package wiki.yexin.quickjs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the bundled {@code yeow-quickjs} native library.
 *
 * <p>Resolution order: {@code java.library.path} first, then the classpath
 * resource {@code native/<platform>/<libname>}, extracted into
 * {@code <tmp>/yeow-quickjs/}. On Windows a loaded DLL is locked, so when the
 * canonical file cannot be overwritten (several JVMs on one machine) a fresh
 * per-process file is used instead.
 */
final class QuickJSNativeLoader {

    private static final String LIB = "yeow-quickjs";
    private static volatile boolean loaded;

    private QuickJSNativeLoader() {}

    static synchronized void load() {
        if (loaded) return;

        try {
            System.loadLibrary(LIB);
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {
            // fall through to classpath extraction
        }

        String platform = detectPlatform();
        if (platform == null) {
            throw new QuickJSException("Unsupported platform: " + System.getProperty("os.name")
                    + " / " + System.getProperty("os.arch"));
        }

        String resource = "native/" + platform + "/" + mapLibName(platform);
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "yeow-quickjs");
        try {
            Files.createDirectories(dir);
            Path target = extract(dir, resource, mapLibName(platform));
            if (target == null) {
                throw new QuickJSException("Native library not found on classpath: " + resource);
            }
            System.load(target.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new QuickJSException("Failed to extract native library: " + e.getMessage(), e);
        } catch (UnsatisfiedLinkError e) {
            throw new QuickJSException("Failed to load native library: " + e.getMessage(), e);
        }
    }

    private static Path extract(Path dir, String resource, String libName) throws IOException {
        Path canonical = dir.resolve(libName);
        try (InputStream is = QuickJSNativeLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return null;
            try {
                Files.copy(is, canonical, StandardCopyOption.REPLACE_EXISTING);
                return canonical;
            } catch (IOException locked) {
                // Canonical file is locked by another JVM: use a per-process file.
                Path unique = dir.resolve(uniqueLibName(libName));
                try (InputStream again = QuickJSNativeLoader.class.getClassLoader().getResourceAsStream(resource)) {
                    if (again == null) return canonical;
                    Files.copy(again, unique, StandardCopyOption.REPLACE_EXISTING);
                }
                return unique;
            }
        }
    }

    private static String uniqueLibName(String libName) {
        int dot = libName.lastIndexOf('.');
        String stem = dot > 0 ? libName.substring(0, dot) : libName;
        String ext = dot > 0 ? libName.substring(dot) : "";
        return stem + "-" + ProcessHandle.current().pid() + "-" + Long.toHexString(System.nanoTime()) + ext;
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
            arch = "x86_64";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            arch = "arm64";
        } else {
            return null;
        }
        if (os.contains("win")) return "windows-" + arch;
        if (os.contains("mac") || os.contains("darwin")) return "macos-" + arch;
        if (os.contains("nux") || os.contains("nix")) return "linux-" + arch;
        return null;
    }

    private static String mapLibName(String platform) {
        if (platform.startsWith("windows")) return LIB + ".dll";
        if (platform.startsWith("macos")) return "lib" + LIB + ".dylib";
        return "lib" + LIB + ".so";
    }
}
