package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

public class PlayerTasks {
    public static Object get(JsonObject p) { var id = p.has("identifier") ? p.get("identifier").getAsString() : null; var pl = resolve(id); return pl != null ? Map.of("uuid",pl.getUniqueId().toString(),"name",pl.getName()) : null; }
    public static Object getAll() { return Bukkit.getOnlinePlayers().stream().map(x->Map.of("uuid",x.getUniqueId().toString(),"name",x.getName())).toList(); }
    public static Object getPing(JsonObject p) { return player(p).getPing(); }
    public static Object getGamemode(JsonObject p) { return player(p).getGameMode().name(); }
    public static Object setGamemode(JsonObject p) { player(p).setGameMode(GameMode.valueOf(p.get("value").getAsString())); return true; }
    public static Object getHealth(JsonObject p) { return player(p).getHealth(); }
    public static Object setHealth(JsonObject p) { player(p).setHealth(p.get("value").getAsDouble()); return true; }
    public static Object sendMessage(JsonObject p) { player(p).sendMessage(TextUtil.parse(p.get("message").getAsString())); return true; }
    public static Object kick(JsonObject p) { player(p).kickPlayer(p.has("reason")?p.get("reason").getAsString():null); return true; }
    public static Object getFood(JsonObject p) { return player(p).getFoodLevel(); }
    public static Object setFood(JsonObject p) { player(p).setFoodLevel(p.get("value").getAsInt()); return true; }
    public static Object getExp(JsonObject p) { return (double)player(p).getExp(); }
    public static Object setExp(JsonObject p) { player(p).setExp(p.get("value").getAsFloat()); return true; }
    public static Object getLevel(JsonObject p) { return player(p).getLevel(); }
    public static Object setLevel(JsonObject p) { player(p).setLevel(p.get("value").getAsInt()); return true; }
    public static Object getOp(JsonObject p) { return player(p).isOp(); }
    public static Object getAllowFlight(JsonObject p) { return player(p).getAllowFlight(); }
    public static Object setAllowFlight(JsonObject p) { player(p).setAllowFlight(p.get("value").getAsBoolean()); return true; }
    public static Object getFlying(JsonObject p) { return player(p).isFlying(); }
    public static Object setFlying(JsonObject p) { player(p).setFlying(p.get("value").getAsBoolean()); return true; }
    public static Object getWalkSpeed(JsonObject p) { return (double)player(p).getWalkSpeed(); }
    public static Object setWalkSpeed(JsonObject p) { player(p).setWalkSpeed(p.get("value").getAsFloat()); return true; }
    public static Object getFlySpeed(JsonObject p) { return (double)player(p).getFlySpeed(); }
    public static Object setFlySpeed(JsonObject p) { player(p).setFlySpeed(p.get("value").getAsFloat()); return true; }
    public static Object getWorld(JsonObject p) { return player(p).getWorld().getName(); }
    public static Object getLocation(JsonObject p) { var l = player(p).getLocation(); return Map.of("x",l.getX(),"y",l.getY(),"z",l.getZ(),"yaw",(double)l.getYaw(),"pitch",(double)l.getPitch(),"world",l.getWorld().getName()); }
    public static Object getDisplayName(JsonObject p) { return player(p).getDisplayName(); }
    public static Object setDisplayName(JsonObject p) { player(p).setDisplayName(p.has("value")&&!p.get("value").isJsonNull()?p.get("value").getAsString():null); return true; }
    public static Object getSaturation(JsonObject p) { return (double)player(p).getSaturation(); }
    public static Object getTotalExperience(JsonObject p) { return player(p).getTotalExperience(); }
    public static Object sendTitle(JsonObject p) {
        var pl = player(p);
        pl.sendTitle(p.has("title")?p.get("title").getAsString():"", p.has("subtitle")?p.get("subtitle").getAsString():"", p.has("fadeIn")?p.get("fadeIn").getAsInt():10, p.has("stay")?p.get("stay").getAsInt():70, p.has("fadeOut")?p.get("fadeOut").getAsInt():20);
        return true;
    }
    public static Object playSound(JsonObject p) {
        var pl = player(p);
        var sound = p.has("sound")?p.get("sound").getAsString():"block.note_block.pling";
        var volume = p.has("volume")?(float)p.get("volume").getAsDouble():1.0f;
        var pitch = p.has("pitch")?(float)p.get("pitch").getAsDouble():1.0f;
        try { pl.playSound(pl.getLocation(), sound, org.bukkit.SoundCategory.MASTER, volume, pitch); } catch(Exception e) { /* sound not found */ }
        return true;
    }
    public static Object giveExp(JsonObject p) { player(p).giveExp(p.get("amount").getAsInt()); return true; }
    public static Object hasPermission(JsonObject p) { return player(p).hasPermission(p.get("permission").getAsString()); }
    public static Object teleport(JsonObject p) { player(p).teleport(new Location(Bukkit.getWorld(p.get("world").getAsString()),p.get("x").getAsDouble(),p.get("y").getAsDouble(),p.get("z").getAsDouble(),(float)p.get("yaw").getAsDouble(),(float)p.get("pitch").getAsDouble())); return true; }
    public static Object sendActionBar(JsonObject p) { player(p).sendActionBar(TextUtil.parse(p.get("message").getAsString())); return true; }
    public static Object sendResourcePack(JsonObject p) {
        var pl = player(p);
        var url = p.get("url").getAsString();
        var hash = p.has("hash") && !p.get("hash").isJsonNull() ? p.get("hash").getAsString() : "";
        var prompt = p.has("prompt") && !p.get("prompt").isJsonNull()
            ? TextUtil.parse(p.get("prompt").getAsString()) : net.kyori.adventure.text.Component.empty();
        var force = p.has("force") && p.get("force").getAsBoolean();
        try {
            var method = Player.class.getMethod("setResourcePack", String.class, String.class, net.kyori.adventure.text.Component.class, boolean.class);
            method.invoke(pl, url, hash, prompt, force);
        } catch (Exception e) {
            pl.setResourcePack(url, hash);
        }
        return true;
    }
    public static Object isOnline(JsonObject p) {
        return Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString())) != null;
    }
    public static Object getItemInMainHand(JsonObject p) {
        var pl = player(p);
        var item = pl.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return null;
        return serializeItem(item);
    }
    public static Object getItemInOffHand(JsonObject p) {
        var pl = player(p);
        var item = pl.getInventory().getItemInOffHand();
        if (item.getType() == Material.AIR) return null;
        return serializeItem(item);
    }
    private static Object serializeItem(org.bukkit.inventory.ItemStack item) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", item.getType().getKey().toString());
        m.put("amount", item.getAmount());
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            var metaMap = new LinkedHashMap<String, Object>();
            if (meta.hasDisplayName()) metaMap.put("displayName", TextUtil.toLegacy(meta.displayName()));
            if (meta.hasLore()) metaMap.put("lore", meta.lore().stream().map(TextUtil::toLegacy).toList());
            if (meta.hasCustomModelData()) metaMap.put("customModelData", meta.getCustomModelData());
            if (meta.isUnbreakable()) metaMap.put("unbreakable", true);
            if (meta.hasEnchants()) {
                var enchs = new LinkedHashMap<String, Object>();
                meta.getEnchants().forEach((ench, lvl) -> enchs.put(ench.getKey().toString(), lvl));
                metaMap.put("enchantments", enchs);
            }
            m.put("meta", metaMap);
        }
        return m;
    }

    static Player player(JsonObject p) { var pl = Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString())); if (pl == null) throw new IllegalArgumentException("Player not found"); return pl; }
    static Player resolve(String id) {
        if (id == null) return null;
        if (id.contains("-")&&id.length()==36) try{return Bukkit.getPlayer(UUID.fromString(id));}catch(Exception ignored){}
        return Bukkit.getPlayer(id);
    }
}
