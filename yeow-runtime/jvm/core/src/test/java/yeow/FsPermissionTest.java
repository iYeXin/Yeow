package yeow;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** fs 三级权限模型：plugin 免声明；server/outer 需声明（通配/节点级）。 */
class FsPermissionTest {

    /** 测试用最小平台桥（不触碰 Bukkit）。 */
    private static PlatformHost stubHost() {
        return new PlatformHost() {
            @Override public Logger logger() { return Logger.getLogger("test"); }
            @Override public String minecraftVersion() { return "1.21.4"; }
            @Override public String runtimeVersion() { return "test"; }
            @Override public String platformName() { return "test"; }
            @Override public File dataFolder() { return new File("target/test-data"); }
            @Override public boolean isGameThread() { return true; }
            @Override public void onGameThread(Runnable r) { r.run(); }
            @Override public Object executeTask(String taskType, com.google.gson.JsonObject params) { return null; }
            @Override public void purgePlatformResources(String pluginName) {}
            @Override public void syncCommands() {}
        };
    }

    private static PluginThread pt(Set<String> perms) {
        var core = new RuntimeCore(stubHost());
        return new PluginThread("t", "x.jar", "init", "code", core, perms, java.util.Map.of());
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

    @Test
    void displayExpandsFsWildcard() throws Exception {
        var m = RuntimeCore.class.getDeclaredMethod("displayPermissions", java.util.Set.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var s = (String) m.invoke(null, new java.util.LinkedHashSet<>(java.util.List.of("fs:*", "http:*", "service:registerNative")));
        assertEquals("fs:outer.*, fs:server.*, http:*, service:registerNative", s);
        var s2 = (String) m.invoke(null, new java.util.LinkedHashSet<>(java.util.List.of("fs:server.readFile")));
        assertEquals("fs:server.readFile", s2);
    }
}
