package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
        if (pl != null) {
            var sound = p.get("sound").getAsString();
            try { pl.stopSound(Sound.valueOf(sound.toUpperCase())); } catch (Exception e) { pl.stopAllSounds(); }
        }
        return true;
    }
}
