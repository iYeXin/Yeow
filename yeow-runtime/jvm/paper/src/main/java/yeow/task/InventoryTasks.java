package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.*;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

public class InventoryTasks {
    static org.bukkit.entity.Player player(JsonObject p) {
        return Bukkit.getPlayer(java.util.UUID.fromString(p.get("uuid").getAsString()));
    }

    public static Object getItem(JsonObject p) {
        var pl = player(p); var item = pl.getInventory().getItem(p.get("slot").getAsInt());
        return item != null && item.getType() != Material.AIR ? Map.of("type", item.getType().getKey().toString(), "amount", item.getAmount()) : null;
    }
    public static Object setItem(JsonObject p) {
        var pl = player(p); var mat = Material.matchMaterial(p.get("itemType").getAsString());
        pl.getInventory().setItem(p.get("slot").getAsInt(), new ItemStack(mat, p.has("amount")?p.get("amount").getAsInt():1));
        return true;
    }
    public static Object addItem(JsonObject p) {
        var pl = player(p); var mat = Material.matchMaterial(p.get("itemType").getAsString());
        var left = pl.getInventory().addItem(new ItemStack(mat, p.has("amount")?p.get("amount").getAsInt():1));
        if (!left.isEmpty()) pl.getWorld().dropItem(pl.getLocation(), left.get(0));
        return true;
    }
    public static Object removeItem(JsonObject p) {
        player(p).getInventory().removeItem(new ItemStack(Material.matchMaterial(p.get("itemType").getAsString()), p.has("amount")?p.get("amount").getAsInt():64));
        return true;
    }
    public static Object clear(JsonObject p) {
        var pl = player(p);
        if (p.has("slot")) pl.getInventory().setItem(p.get("slot").getAsInt(), null);
        else pl.getInventory().clear();
        return true;
    }
}
