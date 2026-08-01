package com.whl.quickjs.wrapper;

import java.io.*;
import java.nio.file.*;

final class QuickJSNativeLoader {

    private static final String LIB_NAME = "quickjs-java-wrapper";
    private static volatile boolean loaded;
    private static volatile String loadError;

    private QuickJSNativeLoader() {}

    static boolean isLoaded() { return loaded; }
    static String getLoadError() { return loadError; }

    static synchronized void load() {
        if (loaded) return;

        // 1. Try java.library.path first
        try {
            System.loadLibrary(LIB_NAME);
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError e) {
            loadError = "java.library.path: " + e.getMessage();
        }

        // 2. Try bundled native lib from classpath
        String platform = detectPlatform();
        if (platform != null) {
            String dir = "native/" + platform + "/";
            String libName = mapLibName(platform);
            String resourcePath = dir + libName;
            try {
                Path extractDir = Path.of(System.getProperty("java.io.tmpdir"), "yeow-quickjs");
                Files.createDirectories(extractDir);

                Path libFile = extractLib(extractDir, libName, resourcePath);
                if (libFile == null) {
                    loadError = "Resource not found on classpath: " + resourcePath;
                } else {
                    extractAndLoadDeps(platform, dir, extractDir);
                    try {
                        System.load(libFile.toAbsolutePath().toString());
                        loaded = true;
                        return;
                    } catch (UnsatisfiedLinkError e) {
                        loadError = "System.load(" + libFile + "): " + e.getMessage();
                    }
                }
            } catch (IOException e) {
                loadError = "Extraction failed: " + e.getMessage();
            }
        } else {
            loadError = "Unsupported platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch");
        }

        throw new QuickJSException(
            "Failed to load native library for platform '" + detectPlatform() + "'. " +
            "Expected: native/" + detectPlatform() + "/" + mapLibName(detectPlatform()) + ". " +
            (loadError != null ? "Cause: " + loadError + ". " : "") +
            "Make sure the native library is on java.library.path or bundled in the JAR."
        );
    }

    /**
     * Extract a bundled native lib into {@code %TEMP%/yeow-quickjs/} and return the file to load.
     *
     * <p>Windows locks loaded DLLs: on a machine running multiple server processes (e.g. several
     * Minecraft servers with the Yeow runtime), overwriting the canonical extracted file fails
     * with {@code AccessDeniedException}. In that case the library is copied to a fresh
     * <b>per-process unique file</b> in the same directory and loaded from there — so any number
     * of JVMs can run side by side.</p>
     *
     * @return the file to load, or {@code null} if the resource does not exist
     */
    private static Path extractLib(Path extractDir, String libName, String resourcePath) throws IOException {
        Path canonical = extractDir.resolve(libName);
        InputStream is = QuickJSNativeLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) return null;
        try {
            Files.copy(is, canonical, StandardCopyOption.REPLACE_EXISTING);
            return canonical;
        } catch (IOException e) {
            // Canonical file is locked by another JVM — copy a unique per-process file instead.
            Path unique = extractDir.resolve(uniqueLibName(libName));
            try (InputStream again = QuickJSNativeLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (again == null) return canonical;
                Files.copy(again, unique, StandardCopyOption.REPLACE_EXISTING);
            }
            return unique;
        } finally {
            is.close();
        }
    }

    /** <name>-<pid>-<nano>.<ext> — never collides with the canonical file or other processes. */
    private static String uniqueLibName(String libName) {
        int dot = libName.lastIndexOf('.');
        String stem = dot > 0 ? libName.substring(0, dot) : libName;
        String ext = dot > 0 ? libName.substring(dot) : "";
        long pid = ProcessHandle.current().pid();
        return stem + "-" + pid + "-" + Long.toHexString(System.nanoTime()) + ext;
    }

    /**
     * Extract known companion libs and load them into the process.
     * All platforms use System.load() for dependencies — Windows DLL
     * search does NOT automatically include the loaded DLL's directory.
     */
    private static void extractAndLoadDeps(String platform, String dir, Path extractDir) {
        String[] deps;
        if (platform.startsWith("windows")) {
            deps = new String[]{"libwinpthread-1.dll"};
        } else if (platform.startsWith("linux")) {
            deps = new String[]{"libquickjs.so", "libquickjs.so.0"};
        } else if (platform.startsWith("macos")) {
            deps = new String[]{"libquickjs.dylib"};
        } else {
            deps = new String[0];
        }

        for (String dep : deps) {
            try {
                Path depFile = extractLib(extractDir, dep, dir + dep);
                if (depFile == null) continue;
                System.load(depFile.toAbsolutePath().toString());
            } catch (Exception ignored) {}
        }
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) arch = "x86_64";
        else if (arch.contains("aarch64") || arch.contains("arm64")) arch = "arm64";
        else return null;
        if (os.contains("win")) return "windows-" + arch;
        if (os.contains("mac") || os.contains("darwin")) return "macos-" + arch;
        if (os.contains("nux") || os.contains("nix")) return "linux-" + arch;
        return null;
    }

    private static String mapLibName(String platform) {
        if (platform == null) return LIB_NAME;
        if (platform.startsWith("windows")) return "lib" + LIB_NAME + ".dll";
        if (platform.startsWith("macos")) return "lib" + LIB_NAME + ".dylib";
        return "lib" + LIB_NAME + ".so";
    }
}
