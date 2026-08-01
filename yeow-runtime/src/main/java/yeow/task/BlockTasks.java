package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.*;

public class BlockTasks {
    public static Object isSolid(JsonObject p) { return block(p).getType().isSolid(); }
    public static Object isLiquid(JsonObject p) { return block(p).isLiquid(); }
    public static Object isEmpty(JsonObject p) { return block(p).isEmpty(); }

    public static Object breakNaturally(JsonObject p) {
        var b = block(p);
        if (p.has("item")) {
            var tool = GuiTasks.buildItem(p.getAsJsonObject("item"));
            return b.breakNaturally(tool);
        }
        return b.breakNaturally();
    }

    static org.bukkit.block.Block block(JsonObject p) {
        return Bukkit.getWorld(p.get("world").getAsString()).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
    }
}
