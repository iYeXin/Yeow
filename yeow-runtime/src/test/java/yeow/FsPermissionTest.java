package yeow;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** fs 三级权限模型：plugin 免声明；server/outer 需声明（通配/节点级）。 */
class FsPermissionTest {

    private static PluginThread pt(Set<String> perms) {
        var cfg = new YeowConfig(new File("target/test-data"));
        var sched = new Scheduler(cfg);
        return new PluginThread("t", "x.jar", "init", "code", sched, perms);
    }

    private static String check(PluginThread pt, String channel, String op) throws Exception {
        var m = PluginThread.class.getDeclaredMethod("checkChannelPermission", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(pt, channel, op);
    }

    @Test
    void pluginLevelNeedsNoPermission() throws Exception {
        var pt = pt(Set.of());
        assertNull(check(pt, "fs", "plugin.readFile"));
        assertNull(check(pt, "fs", "plugin.writeFile"));
    }

    @Test
    void serverLevelDeniedWithoutDeclaration() throws Exception {
        var pt = pt(Set.of());
        assertNotNull(check(pt, "fs", "server.readFile"));
        assertTrue(check(pt, "fs", "server.readFile").contains("fs:server.readFile"));
    }

    @Test
    void serverLevelAllowedWithLevelWildcard() throws Exception {
        var pt = pt(Set.of("fs:server.*"));
        assertNull(check(pt, "fs", "server.readFile"));
        assertNull(check(pt, "fs", "server.writeFile"));
        assertNotNull(check(pt, "fs", "outer.readFile"));
    }

    @Test
    void serverLevelAllowedWithGranularNode() throws Exception {
        var pt = pt(Set.of("fs:server.readFile"));
        assertNull(check(pt, "fs", "server.readFile"));
        assertNotNull(check(pt, "fs", "server.writeFile"));
    }

    @Test
    void channelWildcardCoversAllLevels() throws Exception {
        var pt = pt(Set.of("fs:*"));
        assertNull(check(pt, "fs", "server.readFile"));
        assertNull(check(pt, "fs", "outer.writeFile"));
    }

    @Test
    void outerRequiresDeclaration() throws Exception {
        var pt = pt(Set.of("fs:outer.*"));
        assertNull(check(pt, "fs", "outer.readFile"));
        assertNull(check(pt, "fs", "outer.readBase64"));
        assertNotNull(check(pt, "fs", "server.readFile"));
    }
}
