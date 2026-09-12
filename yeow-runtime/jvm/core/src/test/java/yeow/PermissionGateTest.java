package yeow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一权限门控：模式匹配 + 判定顺序（deny 优先 / allow 白名单 / 继承 / 默认策略）。
 */
class PermissionGateTest {

    @Test
    void patternMatching() {
        assertTrue(PermissionGate.matches("*", "fs:server.readFile"));
        assertTrue(PermissionGate.matches("fs:*", "fs:server.readFile"));
        assertTrue(PermissionGate.matches("fs:*", "fs:outer.writeFile"));
        assertTrue(PermissionGate.matches("fs:server.*", "fs:server.readFile"));
        assertTrue(PermissionGate.matches("task:player.*", "task:player.get"));
        assertTrue(PermissionGate.matches("fs:server.readFile", "fs:server.readFile"));
        assertFalse(PermissionGate.matches("fs:server.*", "fs:outer.readFile"));
        assertFalse(PermissionGate.matches("task:player.*", "task:world.setBlock"));
    }

    @Test
    void denyWinsOverGrants() {
        var grants = Set.of("fs:server.*");
        assertNotNull(PermissionGate.check(grants, null, List.of("fs:*"), "fs:server.readFile"));
        assertNotNull(PermissionGate.check(grants, null, List.of("*"), "fs:server.readFile"));
    }

    @Test
    void denyAllBlocksTask() {
        // deny:['*'] = 只允许标准 ES 代码（task 默认拥有也被 deny 覆盖）
        assertNotNull(PermissionGate.check(Set.of("task:*"), null, List.of("*"), "task:player.get"));
    }

    @Test
    void allowIsWhitelist() {
        var grants = Set.of("fs:server.*", "http:*");
        var allow = List.of("task:player.*");
        assertNull(PermissionGate.check(grants, allow, List.of(), "task:player.get"));
        assertNotNull(PermissionGate.check(grants, allow, List.of(), "http:requestAsync"));
        assertNotNull(PermissionGate.check(grants, allow, List.of(), "fs:server.readFile"));
    }

    @Test
    void noAllowInheritsMainGrants() {
        assertNull(PermissionGate.check(Set.of("http:*"), List.of(), List.of(), "http:requestAsync"));
        assertNull(PermissionGate.check(Set.of("fs:server.*", "fs:outer.*"), null, null, "fs:outer.readFile"));
    }

    @Test
    void allowCannotEscalateBeyondMainGrants() {
        // 主插件未声明 http → allow 无效（仍命中默认拒绝）
        assertNotNull(PermissionGate.check(Set.of(), List.of("http:requestAsync"), List.of(), "http:requestAsync"));
    }

    @Test
    void taskAndUnlistedChannelsAreDefaultOwned() {
        assertNull(PermissionGate.check(Set.of(), null, null, "task:player.get"));
        assertNull(PermissionGate.check(Set.of(), null, null, "env:"));
        assertNull(PermissionGate.check(Set.of(), null, null, "log:INFO"));
    }

    @Test
    void defaultDeniedPrefixes() {
        assertNotNull(PermissionGate.check(Set.of(), null, null, "fs:server.readFile"));
        assertNotNull(PermissionGate.check(Set.of(), null, null, "fs:outer.writeFile"));
        assertNotNull(PermissionGate.check(Set.of(), null, null, "http:requestAsync"));
        assertNotNull(PermissionGate.check(Set.of(), null, null, "service:registerNative"));
        // 终止回调安全，默认允许（修复旧前缀匹配误伤）
        assertNull(PermissionGate.check(Set.of(), null, null, "service:registerNativeTerminate"));
        // plugin 级 fs 免声明
        assertNull(PermissionGate.check(Set.of(), null, null, "fs:plugin.readFile"));
    }

    @Test
    void nodeForChannels() {
        var taskObj = new com.google.gson.JsonObject();
        taskObj.addProperty("type", "player.get");
        assertEquals("task:player.get", PermissionGate.nodeFor("task", taskObj));
        assertEquals("env:", PermissionGate.nodeFor("env", new com.google.gson.JsonObject()));
        assertNull(PermissionGate.nodeFor("lifecycle", new com.google.gson.JsonObject()));
        assertNull(PermissionGate.nodeFor("debug", new com.google.gson.JsonObject()));
    }
}
