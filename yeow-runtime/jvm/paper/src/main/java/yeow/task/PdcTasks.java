package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;
import java.util.logging.Logger;

public class PdcTasks {
    private static final Logger LOG = Logger.getLogger("Yeow");

    public static Object get(JsonObject p) {
        try {
            var holder = resolveHolder(p);
            if (holder == null) return null;
            var pdc = holder.getPersistentDataContainer();
            if (pdc == null) return null;
            var el = p.get("key");
            if (el == null) return null;
            return pdc.get(makeKey(p, el.getAsString()), PersistentDataType.STRING);
        } catch (Exception e) {
            LOG.warning("[PDC.get] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public static Object set(JsonObject p) {
        try {
            var holder = resolveHolder(p);
            if (holder == null) return false;
            var pdc = holder.getPersistentDataContainer();
            if (pdc == null) return false;
            var elKey = p.get("key");
            var elVal = p.get("value");
            if (elKey == null || elVal == null) return false;
            pdc.set(makeKey(p, elKey.getAsString()), PersistentDataType.STRING, elVal.getAsString());
            return true;
        } catch (Exception e) {
            LOG.warning("[PDC.set] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static Object has(JsonObject p) {
        try {
            var holder = resolveHolder(p);
            if (holder == null) return false;
            var pdc = holder.getPersistentDataContainer();
            if (pdc == null) return false;
            var el = p.get("key");
            if (el == null) return false;
            return pdc.has(makeKey(p, el.getAsString()), PersistentDataType.STRING);
        } catch (Exception e) {
            LOG.warning("[PDC.has] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static Object remove(JsonObject p) {
        try {
            var holder = resolveHolder(p);
            if (holder == null) return false;
            var pdc = holder.getPersistentDataContainer();
            if (pdc == null) return false;
            var el = p.get("key");
            if (el == null) return false;
            pdc.remove(makeKey(p, el.getAsString()));
            return true;
        } catch (Exception e) {
            LOG.warning("[PDC.remove] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static Object keys(JsonObject p) {
        try {
            var holder = resolveHolder(p);
            if (holder == null) return List.of();
            var pdc = holder.getPersistentDataContainer();
            if (pdc == null) return List.of();
            return pdc.getKeys().stream().map(NamespacedKey::toString).toList();
        } catch (Exception e) {
            LOG.warning("[PDC.keys] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    /** 全量读取：{ key → value }（仅本插件命名空间的键）。 */
    public static Object getAll(JsonObject p) {
        try {
            var holder = resolveHolder(p);
            if (holder == null) return Map.of();
            var pdc = holder.getPersistentDataContainer();
            if (pdc == null) return Map.of();
            var ns = pluginNamespace(p);
            var out = new LinkedHashMap<String, Object>();
            for (var k : pdc.getKeys()) {
                if (!k.getNamespace().equals(ns)) continue;
                var v = pdc.get(k, PersistentDataType.STRING);
                if (v != null) out.put(k.getKey(), v);
            }
            return out;
        } catch (Exception e) {
            LOG.warning("[PDC.getAll] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * key → NamespacedKey：无冒号的 key 使用**插件命名空间**（任务参数 `_plugin`，
     * 运行时注入）——不同插件的裸 key 互不冲突（原默认 "yeow" 命名空间为共享空间）。
     */
    static NamespacedKey makeKey(JsonObject p, String key) {
        var namespaced = key.contains(":");
        if (!namespaced) {
            return new NamespacedKey(pluginNamespace(p), key.toLowerCase());
        }
        var parts = key.split(":", 2);
        return new NamespacedKey(parts[0].toLowerCase(), parts[1].toLowerCase());
    }

    /** 插件命名空间（`_plugin` 任务参数，运行时注入）；缺失时回退 "yeow"。 */
    private static String pluginNamespace(JsonObject p) {
        var el = p.get("_plugin");
        return el != null && !el.isJsonNull() && !el.getAsString().isEmpty() ? el.getAsString().toLowerCase() : "yeow";
    }

    static org.bukkit.persistence.PersistentDataHolder resolveHolder(JsonObject p) {
        var idEl = p.get("uuid");
        if (idEl != null && !idEl.isJsonNull()) {
            var id = idEl.getAsString();
            if (!id.isEmpty()) {
                try {
                    var uuid = java.util.UUID.fromString(id);
                    var pl = Bukkit.getPlayer(uuid);
                    if (pl != null) return pl;
                    var ent = Bukkit.getEntity(uuid);
                    if (ent instanceof org.bukkit.persistence.PersistentDataHolder h) return h;
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (p.has("x")) {
            var world = Bukkit.getWorld(p.get("world").getAsString());
            if (world != null) {
                var block = world.getBlockAt(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                if (block.getState() instanceof org.bukkit.persistence.PersistentDataHolder h) return h;
            }
        }
        if (p.has("world")) {
            var world = Bukkit.getWorld(p.get("world").getAsString());
            if (world instanceof org.bukkit.persistence.PersistentDataHolder h) return h;
        }
        return null;
    }
}
