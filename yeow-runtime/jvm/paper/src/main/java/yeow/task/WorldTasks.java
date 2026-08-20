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
    public static Object getDifficulty(JsonObject p) { return world(p).getDifficulty().name().toLowerCase(); }
    public static Object setDifficulty(JsonObject p) { world(p).setDifficulty(Difficulty.valueOf(p.get("value").getAsString().toUpperCase())); return true; }
    public static Object getSpawnLocation(JsonObject p) { var l = world(p).getSpawnLocation(); return Map.of("x",l.getX(),"y",l.getY(),"z",l.getZ(),"yaw",(double)l.getYaw(),"pitch",(double)l.getPitch()); }
    public static Object setSpawnLocation(JsonObject p) { world(p).setSpawnLocation(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()); return true; }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Object getGameRule(JsonObject p) { var r = gameRuleOf(p.get("rule").getAsString()); return r != null ? world(p).getGameRuleValue(r) : null; }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Object setGameRule(JsonObject p) {
        var r = gameRuleOf(p.get("rule").getAsString());
        if (r != null) {
            var v = p.get("value");
            // JsonElement 原样传入 setGameRule 会对布尔/整数规则抛 ClassCastException——
            // 按值的 JSON 类型显式转换（2026-08-13 审计修复）
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) world(p).setGameRule(r, v.getAsBoolean());
            else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) world(p).setGameRule(r, v.getAsInt());
            else world(p).setGameRule(r, v.getAsString());
        }
        return true;
    }
    public static Object getBiome(JsonObject p) { return world(p).getBiome(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getKey().toString(); }
    public static Object getHighestBlockY(JsonObject p) { return world(p).getHighestBlockYAt(p.get("x").getAsInt(), p.get("z").getAsInt()); }
    // ── 世界信息（2026-08-13） ──
    public static Object getSeed(JsonObject p) { return world(p).getSeed(); }
    public static Object getEnvironment(JsonObject p) { return world(p).getEnvironment().name(); }
    public static Object getWorldType(JsonObject p) {
        try { return world(p).getWorldType().name(); } catch (Exception e) { return null; }
    }
    public static Object getGameRules(JsonObject p) {
        // Bukkit World.getGameRules() 返回 UPPER_SNAKE（如 DO_DAYLIGHT_CYCLE）；
        // R3 值域规范：出参严格为驼峰（doDaylightCycle）——做转换
        return java.util.Arrays.stream(world(p).getGameRules()).map(WorldTasks::gameRuleCamel).toList();
    }
    /** UPPER_SNAKE → camelCase（如 DO_DAYLIGHT_CYCLE → doDaylightCycle）；R3 出参驼峰。 */
    private static String gameRuleCamel(String upper) {
        var parts = upper.toLowerCase(java.util.Locale.ROOT).split("_");
        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            var seg = parts[i];
            if (seg.isEmpty()) continue;
            sb.append(Character.toUpperCase(seg.charAt(0))).append(seg.substring(1));
        }
        return sb.toString();
    }

    /** 规则名 → GameRule（入参宽松：驼峰 keepInventory / UPPER_SNAKE KEEP_INVENTORY / 任意大小写与下划线均接受）。 */
    @SuppressWarnings({"unchecked","rawtypes"})
    private static final java.util.Map<String, GameRule> GAME_RULE_BY_NORM = new java.util.HashMap<>();
    static {
        for (var f : GameRule.class.getFields()) {
            try { GAME_RULE_BY_NORM.put(f.getName().replace("_", "").toLowerCase(java.util.Locale.ROOT), (GameRule) f.get(null)); } catch (Exception ignored) {}
        }
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static GameRule gameRuleOf(String name) {
        return name == null ? null : GAME_RULE_BY_NORM.get(name.replace("_", "").toLowerCase(java.util.Locale.ROOT));
    }
    // ── WorldBorder（2026-08-13） ──
    public static Object getBorder(JsonObject p) {
        var b = world(p).getWorldBorder();
        var m = new LinkedHashMap<String, Object>();
        m.put("centerX", b.getCenter().getX());
        m.put("centerZ", b.getCenter().getZ());
        m.put("size", b.getSize());
        m.put("damageAmount", b.getDamageAmount());
        m.put("damageBuffer", b.getDamageBuffer());
        m.put("warningDistance", b.getWarningDistance());
        m.put("warningTime", b.getWarningTime());
        return m;
    }
    public static Object setBorderCenter(JsonObject p) { world(p).getWorldBorder().setCenter(p.get("x").getAsDouble(), p.get("z").getAsDouble()); return true; }
    public static Object setBorderSize(JsonObject p) { world(p).getWorldBorder().setSize(p.get("size").getAsDouble()); return true; }
    public static Object setBorderDamage(JsonObject p) {
        var b = world(p).getWorldBorder();
        if (p.has("amount")) b.setDamageAmount(p.get("amount").getAsDouble());
        if (p.has("buffer")) b.setDamageBuffer(p.get("buffer").getAsDouble());
        return true;
    }
    public static Object setBorderWarning(JsonObject p) {
        var b = world(p).getWorldBorder();
        if (p.has("distance")) b.setWarningDistance(p.get("distance").getAsInt());
        if (p.has("time")) b.setWarningTime(p.get("time").getAsInt());
        return true;
    }
    public static Object setBorderMoving(JsonObject p) {
        var b = world(p).getWorldBorder();
        b.setSize(p.get("from").getAsDouble());
        b.setSize(p.get("to").getAsDouble(), p.get("seconds").getAsLong());
        return true;
    }
    public static Object getChunkAt(JsonObject p) { var c = world(p).getChunkAt(p.get("x").getAsInt(), p.get("z").getAsInt()); return Map.of("x", c.getX(), "z", c.getZ(), "world", c.getWorld().getName()); }
    public static Object isChunkLoaded(JsonObject p) { return world(p).isChunkLoaded(p.get("x").getAsInt(), p.get("z").getAsInt()); }
    public static Object isChunkGenerated(JsonObject p) { return world(p).isChunkGenerated(p.get("x").getAsInt(), p.get("z").getAsInt()); }
    public static Object loadChunk(JsonObject p) { return world(p).loadChunk(p.get("x").getAsInt(), p.get("z").getAsInt(), true); }
    public static Object unloadChunk(JsonObject p) { return world(p).unloadChunk(p.get("x").getAsInt(), p.get("z").getAsInt()); }
    public static Object getBlockLightLevel(JsonObject p) { return world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getLightFromBlocks(); }
    public static Object getSkyLightLevel(JsonObject p) { return world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()).getLightFromSky(); }
    public static Object getBlock(JsonObject p) {
        var b = world(p).getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
        // 从 BlockData.getAsString()（如 "minecraft:stone[waterlogged=true,level=8]"）解析结构化状态；
        // 键值层面保留类型：布尔/数字值输出为对应 JSON 类型（非全部字符串）
        var str = b.getBlockData().getAsString();
        var state = new LinkedHashMap<String, Object>();
        var lb = str.indexOf('[');
        if (lb > 0) {
            for (var pair : str.substring(lb + 1, str.length() - 1).split(",")) {
                var eq = pair.indexOf('=');
                if (eq > 0) state.put(pair.substring(0, eq), stateValue(pair.substring(eq + 1)));
            }
        }
        return Map.of("type", b.getType().getKey().toString(), "x", b.getX(), "y", b.getY(), "z", b.getZ(),
            "state", state, "world", world(p).getName());
    }
    /** 方块状态值文本 → JSON 类型：`true`/`false` → 布尔；数字字面量 → 数字；否则字符串。 */
    private static Object stateValue(String v) {
        if ("true".equals(v)) return true;
        if ("false".equals(v)) return false;
        try {
            if (v.matches("-?\\d+")) return Long.parseLong(v);
            if (v.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?")) return Double.parseDouble(v);
        } catch (Exception ignored) {}
        return v;
    }
    public static Object setBlock(JsonObject p) {
        var mat = Material.matchMaterial(p.get("blockType").getAsString());
        if (mat == null) throw new IllegalArgumentException("Unknown block type: " + p.get("blockType").getAsString());
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
    public static Object dropItem(JsonObject p) {
        var l = new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
        org.bukkit.inventory.ItemStack item;
        if (p.has("item") && p.get("item").isJsonObject()) {
            item = InventoryTasks.buildItem(p.getAsJsonObject("item"));   // 统一物品对象（ItemStack 形状）
        } else {
            item = new ItemStack(Material.matchMaterial(p.get("itemType").getAsString()), p.has("amount") ? p.get("amount").getAsInt() : 1); // 旧式 itemType 兼容
        }
        world(p).dropItem(l, item); return true;
    }
    public static Object strikeLightning(JsonObject p) { world(p).strikeLightning(new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble())); return true; }
    public static Object strikeLightningEffect(JsonObject p) { world(p).strikeLightningEffect(new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble())); return true; }
    public static Object createExplosion(JsonObject p) { world(p).createExplosion(new Location(world(p), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble()), p.has("power")?(float)p.get("power").getAsDouble():4.0f, p.has("setFire")?p.get("setFire").getAsBoolean():false, p.has("breakBlocks")?p.get("breakBlocks").getAsBoolean():true); return true; }
    public static Object spawnEntity(JsonObject p) {
        var w = world(p);
        var type = entityType(p.get("type").getAsString());
        var x = p.get("x").getAsDouble();
        var y = p.get("y").getAsDouble();
        var z = p.get("z").getAsDouble();
        var loc = new Location(w, x, y, z);
        var e = w.spawnEntity(loc, type);
        return e.getUniqueId().toString();
    }
    public static Object spawnItem(JsonObject p) {
        var w = world(p);
        var item = InventoryTasks.buildItem(p.getAsJsonObject("item"));
        var x = p.get("x").getAsDouble();
        var y = p.get("y").getAsDouble();
        var z = p.get("z").getAsDouble();
        var loc = new Location(w, x, y, z);
        var e = w.dropItem(loc, item);
        return e.getUniqueId().toString();
    }

    private static World world(JsonObject p) { return Bukkit.getWorld(p.get("world").getAsString()); }

    /**
     * 实体类型解析：协议统一使用 minecraft 键（如 `minecraft:zombie`，与 entity.getType 输出一致）；
     * 兼容旧式 Bukkit 枚举名（如 `ZOMBIE`）。
     */
    private static org.bukkit.entity.EntityType entityType(String s) {
        var key = NamespacedKey.fromString(s);
        if (key != null) {
            var t = Registry.ENTITY_TYPE.get(key);
            if (t != null) return t;
        }
        return org.bukkit.entity.EntityType.valueOf(s.toUpperCase());
    }
}
