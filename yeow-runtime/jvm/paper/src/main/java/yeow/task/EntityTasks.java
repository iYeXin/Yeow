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
    public static Object getBoundingBox(JsonObject p) { var b = entity(p).getBoundingBox(); return Map.of("minX",b.getMinX(),"minY",b.getMinY(),"minZ",b.getMinZ(),"maxX",b.getMaxX(),"maxY",b.getMaxY(),"maxZ",b.getMaxZ()); }
    public static Object getHealth(JsonObject p) { return ((LivingEntity)entity(p)).getHealth(); }
    public static Object setHealth(JsonObject p) { ((LivingEntity)entity(p)).setHealth(p.get("value").getAsDouble()); return true; }
    public static Object getMaxHealth(JsonObject p) { return ((LivingEntity)entity(p)).getMaxHealth(); }
    public static Object isDead(JsonObject p) { return entity(p).isDead(); }
    public static Object remove(JsonObject p) { entity(p).remove(); return true; }
    public static Object teleport(JsonObject p) { entity(p).teleport(new Location(Bukkit.getWorld(p.get("world").getAsString()),p.get("x").getAsDouble(),p.get("y").getAsDouble(),p.get("z").getAsDouble(),(float)p.get("yaw").getAsDouble(),(float)p.get("pitch").getAsDouble())); return true; }

    // ── 基础补齐（2026-08-13） ──

    public static Object getVelocity(JsonObject p) { var v = entity(p).getVelocity(); return Map.of("x", v.getX(), "y", v.getY(), "z", v.getZ()); }
    public static Object setVelocity(JsonObject p) { entity(p).setVelocity(new org.bukkit.util.Vector(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble())); return true; }
    public static Object getFireTicks(JsonObject p) { return entity(p).getFireTicks(); }
    public static Object setFireTicks(JsonObject p) { entity(p).setFireTicks(p.get("value").getAsInt()); return true; }
    public static Object getTicksLived(JsonObject p) { return entity(p).getTicksLived(); }
    public static Object setTicksLived(JsonObject p) { entity(p).setTicksLived(p.get("value").getAsInt()); return true; }
    public static Object isOnGround(JsonObject p) { return entity(p).isOnGround(); }
    public static Object damage(JsonObject p) {
        var e = living(p);
        var amount = p.get("amount").getAsDouble();
        if (p.has("damager") && !p.get("damager").isJsonNull()) {
            var d = Bukkit.getEntity(UUID.fromString(p.get("damager").getAsString()));
            if (d != null) { e.damage(amount, d); return true; }
        }
        e.damage(amount);
        return true;
    }
    /**
     * 设置目标（AI 行为，**不保证必然生效**——取决于实体类型/寻路能力）。
     * 操作实体 = `uuid`；目标 = `targetUuid`（实体目标，Mob.setTarget）或
     * `world`+`x`+`y`+`z`（位置目标，Pathfinder.moveTo，可带 `speed`）。
     */
    public static Object setTarget(JsonObject p) {
        var e = living(p);
        if (p.has("targetUuid") && !p.get("targetUuid").isJsonNull()) {
            var t = Bukkit.getEntity(UUID.fromString(p.get("targetUuid").getAsString()));
            if (t instanceof LivingEntity le && e instanceof org.bukkit.entity.Mob mob) {
                mob.setTarget(le);
            }
            return true;
        }
        if (p.has("world") && p.has("x") && p.has("y") && p.has("z") && e instanceof org.bukkit.entity.Mob mob) {
            try {
                mob.getPathfinder().moveTo(new Location(Bukkit.getWorld(p.get("world").getAsString()),
                    p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble()),
                    p.has("speed") ? p.get("speed").getAsDouble() : 1.0);
            } catch (Exception ignored) {}
            return true;
        }
        return false;
    }

    static LivingEntity living(JsonObject p) {
        var e = entity(p);
        if (!(e instanceof LivingEntity le)) throw new IllegalArgumentException("Not a living entity");
        return le;
    }

    static Entity entity(JsonObject p) {
        var e = Bukkit.getEntity(UUID.fromString(p.get("uuid").getAsString()));
        if (e == null) throw new IllegalArgumentException("Entity not found");
        return e;
    }
}
