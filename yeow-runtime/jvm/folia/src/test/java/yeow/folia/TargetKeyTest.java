package yeow.folia;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标 key 解析的纯函数单测（不依赖 Bukkit 服务器）。
 * 覆盖：GLOBAL 判定、世界坐标 >>4、区块坐标 c 前缀、无坐标默认 0,0。
 */
class TargetKeyTest {

    @Test
    void isGlobal() {
        assertTrue(TargetKey.isGlobal(null));
        assertTrue(TargetKey.isGlobal(TargetKey.GLOBAL));
        assertFalse(TargetKey.isGlobal("world:world"));
        assertFalse(TargetKey.isGlobal("uuid:abc"));
    }

    @Test
    void chunkCoordsFromWorldCoords() {
        var c = TargetKey.chunkCoords("world:world:0:0");
        assertEquals(0, c[0]);
        assertEquals(0, c[1]);

        var c2 = TargetKey.chunkCoords("world:world:100:64");
        assertEquals(6, c2[0]);
        assertEquals(4, c2[1]);
    }

    @Test
    void chunkCoordsFromNegativeWorldCoords() {
        var c = TargetKey.chunkCoords("world:world:-17:-1");
        assertEquals(-2, c[0]);
        assertEquals(-1, c[1]);
    }

    @Test
    void chunkCoordsChunkPrefixUnchanged() {
        var c = TargetKey.chunkCoords("world:world:c3:c-5");
        assertEquals(3, c[0]);
        assertEquals(-5, c[1]);
    }

    @Test
    void chunkCoordsNoCoordsDefaultsToZero() {
        var c = TargetKey.chunkCoords("world:world");
        assertEquals(0, c[0]);
        assertEquals(0, c[1]);
    }

    @Test
    void chunkCoordsInvalidKeyDefaultsToZero() {
        var c = TargetKey.chunkCoords("uuid:abc");
        assertEquals(0, c[0]);
        assertEquals(0, c[1]);
    }
}
