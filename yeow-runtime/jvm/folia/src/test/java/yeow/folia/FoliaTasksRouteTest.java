package yeow.folia;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 任务路由（getScheduler 的 marker 部分）纯函数单测——驻留标记的确定性来源。
 * 事件桥静态注册表在测试环境为空：event.complete 未注册事件 → GLOBAL。
 */
class FoliaTasksRouteTest {

    private static JsonObject p(String key, Object val) {
        var o = new JsonObject();
        if (val instanceof String s) o.addProperty(key, s);
        else if (val instanceof Number n) o.addProperty(key, n);
        else if (val instanceof Boolean b) o.addProperty(key, b);
        return o;
    }

    private static String marker(String taskType, JsonObject params) {
        return FoliaTasks.getScheduler(taskType, params).marker();
    }

    @Test
    void playerByUuid() {
        assertEquals("uuid:abc-123", marker("player.getPing", p("uuid", "abc-123")));
    }

    @Test
    void playerByIdentifierName() {
        assertEquals("uuid:Notch", marker("player.get", p("identifier", "Notch")));
    }

    @Test
    void entityWithoutIdGoesGlobal() {
        assertEquals(TargetKey.GLOBAL, marker("entity.get", new JsonObject()));
    }

    @Test
    void worldCoordsCarriedIntoMarker() {
        assertEquals("world:world:100:64", marker("world.setBlock", blockParams(100, 64)));
    }

    @Test
    void worldWithoutCoords() {
        assertEquals("world:world", marker("world.getTime", p("world", "world")));
    }

    @Test
    void worldGetByNameParam() {
        assertEquals("world:world2", marker("world.getTime", p("name", "world2")));
    }

    @Test
    void chunkCoordTasksUseCPrefix() {
        assertEquals("world:world:c3:c-5", marker("world.loadChunk", chunkParams(3, -5)));
        assertEquals("world:world:c3:c-5", marker("world.isChunkLoaded", chunkParams(3, -5)));
    }

    @Test
    void pdcByUuid() {
        assertEquals("uuid:abc-123", marker("pdc.get", p("uuid", "abc-123")));
    }

    @Test
    void pdcBlockCarriesCoords() {
        var o = new JsonObject();
        o.addProperty("world", "world");
        o.addProperty("x", 160);
        o.addProperty("z", 32);
        assertEquals("world:world:160:32", marker("pdc.get", o));
    }

    @Test
    void pdcWorldFallsBackToWorldMarker() {
        assertEquals("world:world", marker("pdc.get", p("world", "world")));
    }

    @Test
    void eventCompleteUnknownEventGoesGlobal() {
        assertEquals(TargetKey.GLOBAL, marker("event.complete", p("eventId", "playerJoin#1")));
    }

    @Test
    void globalTaskTypesGoGlobal() {
        assertEquals(TargetKey.GLOBAL, marker("server.broadcast", p("message", "hi")));
        assertEquals(TargetKey.GLOBAL, marker("material.isSolid", p("type", "stone")));
        assertEquals(TargetKey.GLOBAL, marker("command.register", new JsonObject()));
        assertEquals(TargetKey.GLOBAL, marker("unknown.task", new JsonObject()));
        assertEquals(TargetKey.GLOBAL, marker("world.getAll", new JsonObject()));
    }

    private static JsonObject blockParams(int x, int z) {
        var o = new JsonObject();
        o.addProperty("world", "world");
        o.addProperty("x", x);
        o.addProperty("y", 65);
        o.addProperty("z", z);
        return o;
    }

    private static JsonObject chunkParams(int cx, int cz) {
        var o = new JsonObject();
        o.addProperty("world", "world");
        o.addProperty("x", cx);
        o.addProperty("z", cz);
        return o;
    }
}
