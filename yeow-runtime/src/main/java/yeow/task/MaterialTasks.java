package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Material;
import java.util.*;

public class MaterialTasks {
    public static Object getMaterials(JsonObject p) {
        var list = new ArrayList<Map<String, Object>>();
        for (var mat : Registry.MATERIAL) {
            var m = new LinkedHashMap<String, Object>();
            m.put("key", mat.getKey().toString());
            m.put("isBlock", mat.isBlock());
            m.put("isItem", mat.isItem());
            list.add(m);
        }
        return list;
    }

    /** 与 ChunkTasks.blockKeys() 共享同一份缓存——getBlocks 的数组下标即方块类型索引基准。 */
    public static Object getBlocks(JsonObject p) {
        return ChunkTasks.blockKeys();
    }

    public static Object getItems(JsonObject p) {
        var list = new ArrayList<String>();
        for (var mat : Registry.MATERIAL) {
            if (mat.isItem()) list.add(mat.getKey().toString());
        }
        return list;
    }

    // ── 材料级静态判断（不依赖坐标/状态）──

    public static Object isSolid(JsonObject p) {
        return Material.matchMaterial(p.get("type").getAsString()).isSolid();
    }

    public static Object isAir(JsonObject p) {
        return Material.matchMaterial(p.get("type").getAsString()).isAir();
    }

    /** Bukkit Material 无 isLiquid（1.13 起移除）：原版液体方块材质仅水与熔岩。 */
    public static Object isLiquid(JsonObject p) {
        var m = Material.matchMaterial(p.get("type").getAsString());
        return m == Material.WATER || m == Material.LAVA;
    }
}
