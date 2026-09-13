package yeow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `/yeow load` 目标解析：服务器路径 → plugins/Yeow 相对路径 →
 * `&lt;name&gt;-&lt;version&gt;.yeow.zip`（忽略大小写，精确优先，最新版本优先）。
 */
class LoadTargetTest {

    @TempDir
    Path tmp;

    private File touch(String name) throws Exception {
        var f = tmp.resolve(name).toFile();
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[] { 1 });
        return f;
    }

    @Test
    void serverPathWins() throws Exception {
        var f = touch("direct.yeow.zip");
        assertEquals(f, RuntimeCore.resolveLoadTarget(f.getAbsolutePath(), tmp.toFile()));
    }

    @Test
    void relativeToDataDir() throws Exception {
        var f = touch("sub.yeow.zip");
        assertEquals(f, RuntimeCore.resolveLoadTarget("sub.yeow.zip", tmp.toFile()));
    }

    @Test
    void nameMatchesVersionedPackage() throws Exception {
        var f = touch("myPlugin-1.0.0.yeow.zip");
        assertEquals(f, RuntimeCore.resolveLoadTarget("myPlugin", tmp.toFile()));
    }

    @Test
    void caseInsensitiveButExactPreferred() throws Exception {
        var ci = touch("myplugin-1.0.0.yeow.zip");       // wrong case, but made newest
        var exact = touch("myPlugin-2.0.0.yeow.zip");    // exact case
        ci.setLastModified(System.currentTimeMillis() + 60_000);
        // Exact-case prefix wins even though the wrong-case file is newer.
        assertEquals(exact, RuntimeCore.resolveLoadTarget("myPlugin", tmp.toFile()));
        // No exact-case match for "MyPlugin" → case-insensitive fallback, newest wins.
        assertEquals(ci, RuntimeCore.resolveLoadTarget("MyPlugin", tmp.toFile()));
    }

    @Test
    void newestVersionWins() throws Exception {
        var recent = touch("p-2.0.0.yeow.zip");
        touch("p-1.0.0.yeow.zip");
        recent.setLastModified(System.currentTimeMillis() + 10_000);
        assertEquals(recent, RuntimeCore.resolveLoadTarget("p", tmp.toFile()));
    }

    @Test
    void unrelatedPrefixDoesNotMatch() throws Exception {
        touch("myPluginExtra-1.0.0.yeow.zip");
        assertNull(RuntimeCore.resolveLoadTarget("myPlugin", tmp.toFile()));
    }

    @Test
    void notFoundReturnsNull() {
        assertNull(RuntimeCore.resolveLoadTarget("nope", tmp.toFile()));
        assertNull(RuntimeCore.resolveLoadTarget("", tmp.toFile()));
        assertNull(RuntimeCore.resolveLoadTarget(null, tmp.toFile()));
    }
}
