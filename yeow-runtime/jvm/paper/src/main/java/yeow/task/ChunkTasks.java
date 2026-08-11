package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk 快照任务：把方块类型索引数组（short[]）base64 编码后交给 JS 侧。
 *
 * 索引基准：方块类型索引 = {@link MaterialTasks#getBlocks} 返回数组的下标
 * （Registry.MATERIAL 迭代中 isBlock 的顺序）。两侧共享同一份缓存
 * （{@link #BLOCK_KEYS}），保证顺序与内容严格一致。索引仅当前运行时有效。
 */
public class ChunkTasks {
    private static volatile List<String> BLOCK_KEYS;
    private static volatile Map<String, Short> KEY_TO_INDEX;
    private static volatile short AIR_INDEX;

    /** 与 MaterialTasks.getBlocks 完全一致的方块 key 列表（运行时缓存一次）。 */
    public static List<String> blockKeys() {
        var keys = BLOCK_KEYS;
        if (keys != null) return keys;
        synchronized (ChunkTasks.class) {
            if (BLOCK_KEYS == null) {
                var list = new ArrayList<String>();
                var map = new HashMap<String, Short>();
                short idx = 0;
                for (var mat : Registry.MATERIAL) {
                    if (mat.isBlock()) {
                        var key = mat.getKey().toString();
                        list.add(key);
                        map.put(key, idx);
                        if ("minecraft:air".equals(key)) AIR_INDEX = idx;
                        idx++;
                    }
                }
                BLOCK_KEYS = List.copyOf(list);
                KEY_TO_INDEX = Map.copyOf(map);
            }
            return BLOCK_KEYS;
        }
    }

    private static short indexOf(String key) {
        return KEY_TO_INDEX.getOrDefault(key, AIR_INDEX);
    }

    private static World world(JsonObject p) { return Bukkit.getWorld(p.get("world").getAsString()); }

    /** 完整方块快照：{ data: base64(short[]), minY, height }，遍历顺序 y 外层 → z → x。 */
    public static Object getSnapshot(JsonObject p) {
        blockKeys(); // 确保索引缓存已初始化（可能先于任何 server.getBlocks 调用）
        var w = world(p);
        var chunk = w.getChunkAt(p.get("x").getAsInt(), p.get("z").getAsInt());
        var snap = chunk.getChunkSnapshot();
        int minY = w.getMinHeight();
        int height = w.getMaxHeight() - minY;
        var idx = new short[16 * 16 * height];
        int n = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    idx[n++] = indexOf(snap.getBlockData(x, y + minY, z).getMaterial().getKey().toString());
                }
            }
        }
        return Map.of("data", encode(idx), "minY", minY, "height", height);
    }

    /** 顶部方块快照：{ data: base64(short[256]) [, height: base64(short[256])] }，
     *  每列最高非空气方块（z 外层 → x 内层）。请求带 withHeight=true 时同时返回
     *  每列最高方块的世界高度（heightMap，与 data 同布局、同编码）。
     *  用 World.getHighestBlockYAt（世界坐标）直接查询，避免构造 ChunkSnapshot 的开销。 */
    public static Object getTopSnapshot(JsonObject p) {
        blockKeys(); // 确保索引缓存已初始化（可能先于任何 server.getBlocks 调用）
        var w = world(p);
        int cx = p.get("x").getAsInt();
        int cz = p.get("z").getAsInt();
        var chunk = w.getChunkAt(cx, cz);
        int minY = w.getMinHeight();
        var idx = new short[256];
        var heights = new short[256];
        int n = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = w.getHighestBlockYAt(cx * 16 + x, cz * 16 + z);
                heights[n] = (short) y;
                if (y < minY) {
                    idx[n++] = AIR_INDEX; // 虚空列
                } else {
                    idx[n++] = indexOf(chunk.getBlock(x, y, z).getType().getKey().toString());
                }
            }
        }
        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("data", encode(idx));
        if (p.has("withHeight") && p.get("withHeight").getAsBoolean()) {
            out.put("height", encode(heights));
        }
        return out;
    }

    /** short[] → little-endian 2 字节/元素 → base64（配合 JS 侧 Uint16Array 零拷贝视图）。 */
    private static String encode(short[] arr) {
        var bytes = new byte[arr.length * 2];
        for (int i = 0; i < arr.length; i++) {
            bytes[i * 2] = (byte) arr[i];
            bytes[i * 2 + 1] = (byte) (arr[i] >> 8);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
