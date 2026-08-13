package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.*;

public class BlockTasks {
    // isSolid/isLiquid/isAir 为材料级静态判断，见 MaterialTasks（material.* 任务）；
    // 本类只保留世界操作（需要坐标）。

    public static Object breakNaturally(JsonObject p) {
        var b = block(p);
        if (p.has("item")) {
            var tool = InventoryTasks.buildItem(p.getAsJsonObject("item"));
            return b.breakNaturally(tool);
        }
        return b.breakNaturally();
    }

    static org.bukkit.block.Block block(JsonObject p) {
        return Bukkit.getWorld(p.get("world").getAsString()).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
    }
}
