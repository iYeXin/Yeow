package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public class WorldTasks {
    public static Object get(JsonObject p) { var w = Bukkit.getWorld(p.get("name").getAsString()); return w != null ? Map.of("name", w.getName()) : null; }
    public static Object getAll() { return Bukkit.getWorlds().stream().map(w -> Map.of("name", w.getName())).toList(); }
    public static Object getTime(JsonObject p) { return world(p).getTime(); }
    public static Object setTime(JsonObject p) { world(p).setTime(p.get("value").getAsLong()); return true; }
    public static Object getStorm(JsonObject p) { return world(p).hasStorm(); }
    public static Object setStorm(JsonObject p) { world(p).setStorm(p.get("value").getAsBoolean()); return true; }
    public static Object getThundering(JsonObject p) { return world(p).isThundering(); }
    public static Object setThundering(JsonObject p) { world(p).setThundering(p.get("value").getAsBoolean()); return true; }
    public static Object getDifficulty(JsonObject p) { return world(p).getDifficulty().name(); }
    public static Object setDifficulty(JsonObject p) { world(p).setDifficulty(Difficulty.valueOf(p.get("value").getAsString())); return true; }
    public static Object getSpawnLocation(JsonObject p) { var l = world(p).getSpawnLocation(); return Map.of("x",l.getX(),"y",l.getY(),"z",l.getZ(),"yaw",(double)l.getYaw(),"pitch",(double)l.getPitch()); }
    public static Object setSpawnLocation(JsonObject p) { world(p).setSpawnLocation(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()); return true; }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Object getGameRule(JsonObject p) { try { var r = (GameRule) GameRule.class.getField(p.get("rule").getAsString().toUpperCase()).get(null); return world(p).getGameRuleValue(r); } catch(Exception e) { return null; } }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Object setGameRule(JsonObject p) { try { var r = (GameRule) GameRule.class.getField(p.get("rule").getAsString().toUpperCase()).get(null); world(p).setGameRule(r, p.get("value")); } catch(Exception e) { /* ignore */ } return true; }
    public static Object getBiome(JsonObject p) { return world(p).getBiome(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getKey().toString(); }
    public static Object getHighestBlockY(JsonObject p) { return world(p).getHighestBlockYAt(p.get("x").getAsInt(), p.get("z").getAsInt()); }
    public static Object getChunkAt(JsonObject p) { var c = world(p).getChunkAt(p.get("x").getAsInt(), p.get("z").getAsInt()); return Map.of("x", c.getX(), "z", c.getZ(), "world", c.getWorld().getName()); }
    public static Object isChunkLoaded(JsonObject p) { return world(p).isChunkLoaded(p.get("x").getAsInt(), p.get("z").getAsInt()); }
    public static Object loadChunk(JsonObject p) { return world(p).loadChunk(p.get("x").getAsInt(), p.get("z").getAsInt(), true); }
    public static Object unloadChunk(JsonObject p) { return world(p).unloadChunk(p.get("x").getAsInt(), p.get("z").getAsInt()); }
    public static Object getBlockLightLevel(JsonObject p) { return world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getLightFromBlocks(); }
    public static Object getSkyLightLevel(JsonObject p) { return world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getLightFromSky(); }
    public static Object getBlock(JsonObject p) {
        var b = world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
        // 从 BlockData.getAsString()（如 "minecraft:stone[waterlogged=true]"）解析结构化状态
        var str = b.getBlockData().getAsString();
        var state = new LinkedHashMap<String, String>();
        var lb = str.indexOf('[');
        if (lb > 0) {
            for (var pair : str.substring(lb + 1, str.length() - 1).split(",")) {
                var eq = pair.indexOf('=');
                if (eq > 0) state.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return Map.of("type", b.getType().getKey().toString(), "x", b.getX(), "y", b.getY(), "z", b.getZ(), "state", state);
    }
    public static Object setBlock(JsonObject p) {
        var mat = Material.matchMaterial(p.get("blockType").getAsString());
        var b = world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
        if (p.has("state") && p.get("state").isJsonObject() && p.getAsJsonObject("state").size() > 0) {
            var sb = new StringBuilder(mat.getKey().toString()).append('[');
            var first = true;
            for (var e : p.getAsJsonObject("state").entrySet()) {
                if (!first) sb.append(',');
                sb.append(e.getKey()).append('=').append(e.getValue().getAsString());
                first = false;
            }
            sb.append(']');
            b.setBlockData(Bukkit.createBlockData(sb.toString()));
        } else {
            b.setType(mat);
        }
        return true;
    }
    public static Object getEntities(JsonObject p) { return world(p).getEntities().stream().map(e -> e.getUniqueId().toString()).toList(); }
    public static Object getPlayers(JsonObject p) { return world(p).getPlayers().stream().map(e -> e.getUniqueId().toString()).toList(); }
    public static Object getNearbyEntities(JsonObject p) { var l = new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble()); return world(p).getNearbyEntities(l, p.get("radius").getAsDouble(), p.get("radius").getAsDouble(), p.get("radius").getAsDouble()).stream().map(e -> e.getUniqueId().toString()).toList(); }
    public static Object dropItem(JsonObject p) { var l = new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble()); world(p).dropItem(l, new ItemStack(Material.matchMaterial(p.get("itemType").getAsString()), p.has("amount")?p.get("amount").getAsInt():1)); return true; }
    public static Object strikeLightning(JsonObject p) { world(p).strikeLightning(new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble())); return true; }
    public static Object strikeLightningEffect(JsonObject p) { world(p).strikeLightningEffect(new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble())); return true; }
    public static Object createExplosion(JsonObject p) { world(p).createExplosion(new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble()), p.has("power")?(float)p.get("power").getAsDouble():4.0f, p.has("setFire")?p.get("setFire").getAsBoolean():false, p.has("breakBlocks")?p.get("breakBlocks").getAsBoolean():true); return true; }
    public static Object spawnEntity(JsonObject p) {
        var w = world(p);
        var type = org.bukkit.entity.EntityType.valueOf(p.get("type").getAsString().toUpperCase());
        var x = p.get("x").getAsDouble();
        var y = p.get("y").getAsDouble();
        var z = p.get("z").getAsDouble();
        var loc = new Location(w, x, y, z);
        var e = w.spawnEntity(loc, type);
        return e.getUniqueId().toString();
    }
    public static Object spawnItem(JsonObject p) {
        var w = world(p);
        var item = GuiTasks.buildItem(p.getAsJsonObject("item"));
        var x = p.get("x").getAsDouble();
        var y = p.get("y").getAsDouble();
        var z = p.get("z").getAsDouble();
        var loc = new Location(w, x, y, z);
        var e = w.dropItem(loc, item);
        return e.getUniqueId().toString();
    }

    private static World world(JsonObject p) { return Bukkit.getWorld(p.get("world").getAsString()); }
}
