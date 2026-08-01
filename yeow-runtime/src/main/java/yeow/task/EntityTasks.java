package yeow.task;

import com.google.gson.JsonObject;
import org.bukkit.*;
import org.bukkit.entity.*;
import java.util.*;

public class EntityTasks {
    public static Object get(JsonObject p) { var e = Bukkit.getEntity(UUID.fromString(p.get("uuid").getAsString())); return e != null ? Map.of("uuid", e.getUniqueId().toString()) : null; }
    public static Object getType(JsonObject p) { return entity(p).getType().name(); }
    public static Object getName(JsonObject p) { return entity(p).getName(); }
    public static Object getCustomName(JsonObject p) { var n = entity(p).getCustomName(); return n != null ? n : ""; }
    public static Object setCustomName(JsonObject p) { entity(p).setCustomName(p.has("value") && !p.get("value").isJsonNull() ? p.get("value").getAsString() : null); return true; }
    public static Object setCustomNameVisible(JsonObject p) { entity(p).setCustomNameVisible(p.get("value").getAsBoolean()); return true; }
    public static Object getWorld(JsonObject p) { return entity(p).getWorld().getName(); }
    public static Object getLocation(JsonObject p) { var l = entity(p).getLocation(); return Map.of("x",l.getX(),"y",l.getY(),"z",l.getZ(),"yaw",(double)l.getYaw(),"pitch",(double)l.getPitch(),"world",l.getWorld().getName()); }
    public static Object isGlowing(JsonObject p) { return entity(p).isGlowing(); }
    public static Object setGlowing(JsonObject p) { entity(p).setGlowing(p.get("value").getAsBoolean()); return true; }
    public static Object isInvulnerable(JsonObject p) { return entity(p).isInvulnerable(); }
    public static Object setInvulnerable(JsonObject p) { entity(p).setInvulnerable(p.get("value").getAsBoolean()); return true; }
    public static Object isSilent(JsonObject p) { return entity(p).isSilent(); }
    public static Object setSilent(JsonObject p) { entity(p).setSilent(p.get("value").getAsBoolean()); return true; }
    public static Object hasGravity(JsonObject p) { return entity(p).hasGravity(); }
    public static Object setGravity(JsonObject p) { entity(p).setGravity(p.get("value").getAsBoolean()); return true; }
    public static Object getPassengers(JsonObject p) { return entity(p).getPassengers().stream().map(e -> e.getUniqueId().toString()).toList(); }
    public static Object getVehicle(JsonObject p) { var v = entity(p).getVehicle(); return v != null ? v.getUniqueId().toString() : null; }
    public static Object getHealth(JsonObject p) { return ((LivingEntity)entity(p)).getHealth(); }
    public static Object setHealth(JsonObject p) { ((LivingEntity)entity(p)).setHealth(p.get("value").getAsDouble()); return true; }
    public static Object getMaxHealth(JsonObject p) { return ((LivingEntity)entity(p)).getMaxHealth(); }
    public static Object isDead(JsonObject p) { return entity(p).isDead(); }
    public static Object remove(JsonObject p) { entity(p).remove(); return true; }
    public static Object teleport(JsonObject p) { entity(p).teleport(new Location(Bukkit.getWorld(p.get("world").getAsString()),p.get("x").getAsDouble(),p.get("y").getAsDouble(),p.get("z").getAsDouble(),(float)p.get("yaw").getAsDouble(),(float)p.get("pitch").getAsDouble())); return true; }

    static Entity entity(JsonObject p) {
        var e = Bukkit.getEntity(UUID.fromString(p.get("uuid").getAsString()));
        if (e == null) throw new IllegalArgumentException("Entity not found");
        return e;
    }
}
