package yeow.task;
import yeow.InstanceRegistry;
import yeow.paper.TextUtil;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GuiTasks {
    static final Map<String, Inventory> guis = new ConcurrentHashMap<>();
    static final Map<String, String> owners = new ConcurrentHashMap<>();
    /** JS 句柄注册表（id → 释放器，由运行时装配；null = 未装配，跳过注册）。 */
    private static volatile InstanceRegistry instances;

    public static void setInstances(InstanceRegistry r) { instances = r; }

    static Inventory resolve(JsonObject p) {
        var id = p.get("id").getAsString();
        var inv = guis.get(id);
        if (inv == null) throw new IllegalArgumentException("GUI not found: " + id);
        return inv;
    }

    /** 释放实例：移除注册/归属，关闭所有查看者，同步注销句柄释放器。 */
    public static void remove(String id) {
        var inv = guis.remove(id);
        owners.remove(id);
        var reg = instances;
        if (reg != null) reg.release(id);
        if (inv != null) {
            inv.getViewers().forEach(v -> { if (v instanceof Player pl) pl.closeInventory(); });
        }
    }

    /** Close and drop every GUI owned by a plugin (called on unload/reload). */
    public static void purgePlugin(String pluginName) {
        owners.entrySet().removeIf(e -> {
            if (!pluginName.equals(e.getValue())) return false;
            remove(e.getKey());
            return true;
        });
    }

    public static Object createGUI(JsonObject p) {
        var size = p.get("size").getAsInt();
        var title = p.get("title").getAsString();
        var inv = Bukkit.createInventory(null, size, TextUtil.toLegacy(TextUtil.parse(title)));
        var id = p.get("id").getAsString();
        guis.put(id, inv);
        owners.put(id, p.has("_plugin") ? p.get("_plugin").getAsString() : "");
        var reg = instances;
        if (reg != null) reg.register(id, () -> remove(id));
        return id;
    }

    public static Object open(JsonObject p) {
        var id = p.get("id").getAsString();
        var inv = guis.get(id);
        if (inv == null) return false;
        var pl = Bukkit.getPlayer(java.util.UUID.fromString(p.get("uuid").getAsString()));
        if (pl != null) pl.openInventory(inv);
        return true;
    }

    public static Object close(JsonObject p) {
        var id = p.get("id").getAsString();
        var inv = guis.get(id);
        if (inv == null) return false;
        inv.getViewers().forEach(v -> { if (v instanceof Player pl) pl.closeInventory(); });
        return true;
    }

    public static Object destroy(JsonObject p) {
        remove(p.get("id").getAsString());
        return true;
    }

    public static Object setItem(JsonObject p) {
        var inv = resolve(p);
        var slot = p.get("slot").getAsInt();
        var item = buildItem(p.getAsJsonObject("item"));
        inv.setItem(slot, item);
        return true;
    }

    public static Object fill(JsonObject p) {
        var inv = resolve(p);
        var item = buildItem(p.getAsJsonObject("item"));
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, item.clone());
        return true;
    }

    public static Object clear(JsonObject p) {
        var inv = resolve(p);
        inv.clear();
        return true;
    }

    static ItemStack buildItem(JsonObject p) {
        if (p == null || !p.has("type")) return new ItemStack(Material.AIR);
        var mat = Material.matchMaterial(p.get("type").getAsString());
        if (mat == null) return new ItemStack(Material.AIR);
        var amount = p.has("amount") ? p.get("amount").getAsInt() : 1;
        var item = new ItemStack(mat, amount);
        if (p.has("meta")) {
            var meta = item.getItemMeta();
            var m = p.getAsJsonObject("meta");
            if (m.has("displayName")) meta.displayName(TextUtil.parse(m.get("displayName").getAsString()));
            if (m.has("lore")) {
                var lore = new ArrayList<net.kyori.adventure.text.Component>();
                for (var el : m.getAsJsonArray("lore")) lore.add(TextUtil.parse(el.getAsString()));
                meta.lore(lore);
            }
            if (m.has("customModelData")) meta.setCustomModelData(m.get("customModelData").getAsInt());
            if (m.has("unbreakable")) meta.setUnbreakable(m.get("unbreakable").getAsBoolean());
            if (m.has("hideTooltip")) meta.setHideTooltip(m.get("hideTooltip").getAsBoolean());
            if (m.has("enchantments")) {
                var enchs = m.getAsJsonObject("enchantments");
                for (var k : enchs.keySet()) {
                    var ench = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.fromString(k));
                    if (ench != null) meta.addEnchant(ench, enchs.get(k).getAsInt(), true);
                }
            }
            if (m.has("itemFlags")) {
                for (var el : m.getAsJsonArray("itemFlags")) {
                    try { meta.addItemFlags(org.bukkit.inventory.ItemFlag.valueOf(el.getAsString())); } catch (Exception ignored) {}
                }
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
