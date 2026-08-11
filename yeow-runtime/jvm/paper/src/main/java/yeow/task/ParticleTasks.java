package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.Color;
import org.bukkit.Particle.DustOptions;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

public class ParticleTasks {

    public static Object spawnParticle(JsonObject p) {
        var world = Bukkit.getWorld(p.get("world").getAsString());
        if (world == null) return false;
        var name = p.get("particle").getAsString();
        var x = p.get("x").getAsDouble();
        var y = p.get("y").getAsDouble();
        var z = p.get("z").getAsDouble();
        var count = p.has("count") ? p.get("count").getAsInt() : 1;
        var ox = p.has("offsetX") ? p.get("offsetX").getAsDouble() : 0.0;
        var oy = p.has("offsetY") ? p.get("offsetY").getAsDouble() : 0.0;
        var oz = p.has("offsetZ") ? p.get("offsetZ").getAsDouble() : 0.0;
        var speed = p.has("speed") ? p.get("speed").getAsDouble() : 0.0;
        var force = p.has("force") && p.get("force").getAsBoolean();

        var particle = Particle.valueOf(name.toUpperCase());
        var loc = new org.bukkit.Location(world, x, y, z);

        if (p.has("color")) {
            var c = p.getAsJsonObject("color");
            var r = c.has("r") ? c.get("r").getAsInt() : 255;
            var g = c.has("g") ? c.get("g").getAsInt() : 255;
            var b = c.has("b") ? c.get("b").getAsInt() : 255;
            var size = c.has("size") ? (float)c.get("size").getAsDouble() : 1.0f;
            world.spawnParticle(particle, loc, count, ox, oy, oz, speed, new DustOptions(Color.fromRGB(r, g, b), size), force);
        } else if (p.has("blockType")) {
            var mat = Material.matchMaterial(p.get("blockType").getAsString());
            if (mat != null) world.spawnParticle(particle, loc, count, ox, oy, oz, speed, mat.createBlockData(), force);
        } else if (p.has("item")) {
            var item = GuiTasks.buildItem(p.getAsJsonObject("item"));
            world.spawnParticle(particle, loc, count, ox, oy, oz, speed, item, force);
        } else {
            world.spawnParticle(particle, loc, count, ox, oy, oz, speed, null, force);
        }
        return true;
    }
}
