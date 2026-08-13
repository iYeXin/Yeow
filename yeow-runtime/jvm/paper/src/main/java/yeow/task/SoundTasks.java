package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import java.util.Map;
import java.util.UUID;

public class SoundTasks {
    public static Object playWorldSound(JsonObject p) {
        var world = Bukkit.getWorld(p.get("world").getAsString());
        if (world == null) return true;
        var sound = p.get("sound").getAsString();
        var x = p.get("x").getAsDouble();
        var y = p.get("y").getAsDouble();
        var z = p.get("z").getAsDouble();
        var volume = p.has("volume") ? (float)p.get("volume").getAsDouble() : 1.0f;
        var pitch = p.has("pitch") ? (float)p.get("pitch").getAsDouble() : 1.0f;
        var loc = new org.bukkit.Location(world, x, y, z);
        try { world.playSound(loc, sound, SoundCategory.MASTER, volume, pitch); } catch (Exception e) { /* sound not found */ }
        return true;
    }

    public static Object stopAllSounds(JsonObject p) {
        var pl = Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString()));
        if (pl != null) pl.stopAllSounds();
        return true;
    }

    public static Object stopSound(JsonObject p) {
        var pl = Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString()));
        if (pl == null) return true;
        var s = p.get("sound").getAsString();
        var key = org.bukkit.NamespacedKey.fromString(s);
        var sound = key != null ? org.bukkit.Registry.SOUNDS.get(key) : null;
        // 未知音效明确报错（原实现 catch 后静默 stopAllSounds——副作用过大，2026-08-13 审计修复）
        if (sound == null) return Map.of("err", "Unknown sound: " + s);
        pl.stopSound(sound);
        return true;
    }
}
