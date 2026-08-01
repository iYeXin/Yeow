package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BossBarTasks {
    static final Map<String, BossBar> bars = new ConcurrentHashMap<>();
    static final Map<String, String> owners = new ConcurrentHashMap<>();

    static BossBar resolve(JsonObject p) {
        var id = p.get("id").getAsString();
        var bb = bars.get(id);
        if (bb == null) throw new IllegalArgumentException("BossBar not found: " + id);
        return bb;
    }

    public static void remove(String id) {
        var bb = bars.remove(id);
        owners.remove(id);
        if (bb != null) { bb.removeAll(); bb.setVisible(false); }
    }

    /** Hide and drop every BossBar owned by a plugin (called on unload/reload). */
    public static void purgePlugin(String pluginName) {
        owners.entrySet().removeIf(e -> {
            if (!pluginName.equals(e.getValue())) return false;
            var bb = bars.remove(e.getKey());
            if (bb != null) { bb.removeAll(); bb.setVisible(false); }
            return true;
        });
    }

    public static String create(JsonObject p) {
        var titleRaw = p.get("title").getAsString();
        var color = BarColor.valueOf(p.has("color") ? p.get("color").getAsString().toUpperCase() : "PURPLE");
        var style = BarStyle.valueOf(p.has("style") ? p.get("style").getAsString().toUpperCase() : "SOLID");
        var bb = Bukkit.createBossBar(TextUtil.toLegacy(TextUtil.parse(titleRaw)), color, style);
        if (p.has("progress")) bb.setProgress(p.get("progress").getAsDouble());
        if (p.has("visible") && !p.get("visible").getAsBoolean()) bb.setVisible(false);
        var id = p.get("id").getAsString();
        bars.put(id, bb);
        owners.put(id, p.has("_plugin") ? p.get("_plugin").getAsString() : "");
        return id;
    }

    public static Object destroy(JsonObject p) { remove(p.get("id").getAsString()); return true; }
    public static Object setTitle(JsonObject p) { resolve(p).setTitle(TextUtil.toLegacy(TextUtil.parse(p.get("title").getAsString()))); return true; }
    public static Object setProgress(JsonObject p) { resolve(p).setProgress(p.get("progress").getAsDouble()); return true; }
    public static Object setColor(JsonObject p) { resolve(p).setColor(BarColor.valueOf(p.get("color").getAsString().toUpperCase())); return true; }
    public static Object setStyle(JsonObject p) { resolve(p).setStyle(BarStyle.valueOf(p.get("style").getAsString().toUpperCase())); return true; }
    public static Object setVisible(JsonObject p) { resolve(p).setVisible(p.get("visible").getAsBoolean()); return true; }
    public static Object addPlayer(JsonObject p) {
        var pl = Bukkit.getPlayer(java.util.UUID.fromString(p.get("uuid").getAsString()));
        if (pl != null) resolve(p).addPlayer(pl);
        return true;
    }
    public static Object removePlayer(JsonObject p) {
        var pl = Bukkit.getPlayer(java.util.UUID.fromString(p.get("uuid").getAsString()));
        if (pl != null) resolve(p).removePlayer(pl);
        return true;
    }
    public static Object removeAll(JsonObject p) { resolve(p).removeAll(); return true; }
    public static Object addFlag(JsonObject p) { resolve(p).addFlag(BarFlag.valueOf(p.get("flag").getAsString().toUpperCase())); return true; }
    public static Object removeFlag(JsonObject p) { resolve(p).removeFlag(BarFlag.valueOf(p.get("flag").getAsString().toUpperCase())); return true; }
}
